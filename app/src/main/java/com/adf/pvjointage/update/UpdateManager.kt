package com.adf.pvjointage.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérifie/télécharge/installe les mises à jour de l'app depuis la release GitHub
 * à tag fixe "tablette-latest" (voir .github/workflows/build-apk.yml et README.md :
 * cette release est republiée à chaque "Run workflow", toujours au même nom
 * d'asset PVJointage.apk).
 *
 * Aucune connexion à un compte GitHub n'est nécessaire : le dépôt est public et
 * l'API/les assets de release sont accessibles anonymement.
 */
object UpdateManager {

    private const val API_URL =
        "https://api.github.com/repos/dabom1993-pixel/PVJointage/releases/tags/tablette-latest"
    private const val ASSET_NAME = "PVJointage.apk"

    // Doit rester identique au nom de base passé à Room.databaseBuilder dans AppDatabase.kt.
    private const val DB_NAME = "pv_jointage.db"

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_ASSET_ID = "last_asset_id"
    private const val KEY_PENDING_ASSET_ID = "pending_asset_id"

    data class UpdateInfo(
        val assetId: Long,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    /** Interroge l'API GitHub (publique, sans authentification) pour l'asset PVJointage.apk de la release. */
    suspend fun fetchLatestUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PVJointage-Android-App")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    return@withContext UpdateInfo(
                        assetId = asset.getLong("id"),
                        downloadUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", -1L)
                    )
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * true si [info] correspond à une version différente de la dernière installée via ce
     * mécanisme. Au tout premier contrôle (aucune version connue en local, ex. juste après
     * l'installation initiale de l'app), on mémorise l'ID courant comme référence au lieu de
     * proposer un re-téléchargement redondant de la version déjà en cours d'exécution.
     */
    fun hasUpdate(context: Context, info: UpdateInfo): Boolean {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_ASSET_ID, -1L)
        if (last == -1L) {
            p.edit().putLong(KEY_LAST_ASSET_ID, info.assetId).apply()
            return false
        }
        return last != info.assetId
    }

    /** Télécharge l'APK dans le stockage privé de l'app, en rapportant la progression (0-100). */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val dest = File(dir, ASSET_NAME)
            if (dest.exists()) dest.delete()

            val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val total = info.sizeBytes.takeIf { it > 0 } ?: conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var readSoFar = 0L
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            readSoFar += n
                            if (total > 0) onProgress(((readSoFar * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            dest
        }

    /** À appeler juste avant de lancer l'intent d'installation : mémorise la version en attente. */
    fun markPendingInstall(context: Context, info: UpdateInfo) {
        prefs(context).edit().putLong(KEY_PENDING_ASSET_ID, info.assetId).apply()
    }

    /**
     * Copie de secours de la base de données locale, juste avant de lancer l'installation.
     *
     * En théorie déjà superflu : tant que le nouvel APK garde le même package et la même
     * signature (clé de debug stable, voir build.gradle.kts), Android traite l'opération comme
     * une simple mise à jour et ne touche jamais aux données de l'app (base Room, préférences,
     * photos, schémas) — contrairement à une désinstallation. Cette copie reste une sécurité
     * supplémentaire, best-effort : une erreur ici ne doit jamais empêcher la mise à jour.
     */
    fun backupDatabaseBeforeInstall(context: Context) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return
            val backupDir = File(context.filesDir, "backup").apply { mkdirs() }
            // Les fichiers -wal/-shm (mode WAL de SQLite) contiennent des écritures pas encore
            // fusionnées dans le fichier principal : à copier aussi s'ils existent.
            listOf("", "-wal", "-shm").forEach { suffix ->
                val src = File(dbFile.parentFile, DB_NAME + suffix)
                if (src.exists()) src.copyTo(File(backupDir, "$DB_NAME$suffix.bak"), overwrite = true)
            }
        } catch (_: Exception) {
            // Best-effort : voir commentaire ci-dessus.
        }
    }

    /** Intent système d'installation de l'APK téléchargé (déclenche la confirmation OS, incontournable). */
    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Appelé par [UpdateInstalledReceiver] quand l'app vient d'être remplacée par la mise à jour
     * en attente : fait juste avancer la référence locale (aucun message affiché à l'utilisateur),
     * pour que le prochain contrôle ne re-propose pas la même version.
     */
    fun onPackageReplaced(context: Context) {
        val p = prefs(context)
        val pending = p.getLong(KEY_PENDING_ASSET_ID, -1L)
        if (pending != -1L) {
            p.edit()
                .putLong(KEY_LAST_ASSET_ID, pending)
                .remove(KEY_PENDING_ASSET_ID)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

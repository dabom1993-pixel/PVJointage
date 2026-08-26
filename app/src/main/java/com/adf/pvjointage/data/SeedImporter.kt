package com.adf.pvjointage.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Importe les données de référence (catalogue des items et des brides) depuis les fichiers
 * CSV extraits de PV_Jointage_-_HA.xlsm (onglets "1-Plan" et "1-Trame" / Tableau1).
 * L'import ne se fait qu'une seule fois (les tables sont vides au premier lancement).
 */
class SeedImporter(private val context: Context, private val db: AppDatabase) {

    suspend fun importIfNeeded() {
        if (db.itemCatalogDao().count() == 0) {
            importItems()
        }
        if (db.brideCatalogDao().count() == 0) {
            importBrides()
        }
        if (db.itemSchemaDao().count() == 0) {
            importSchemas()
        }
    }

    /**
     * Copie le schéma d'exemple (extrait de l'onglet 1-Exemple de votre Excel, ITEM D1153)
     * dans le stockage de l'app et l'enregistre. Les autres items n'ont pas de schéma
     * d'origine : l'utilisateur peut en ajouter un depuis l'écran principal.
     */
    private suspend fun importSchemas() {
        val assetName = "schemas/schema_HDS_Ballon_D1153.png"
        try {
            val destDir = File(context.getExternalFilesDir("schemas") ?: context.filesDir, "")
            destDir.mkdirs()
            val destFile = File(destDir, "schema_HDS_Ballon_D1153.png")
            context.assets.open(assetName).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            db.itemSchemaDao().upsert(
                ItemSchema(unite = "HDS", famille = "Ballon", item = "D1153", filePath = destFile.absolutePath)
            )
        } catch (e: Exception) {
            // pas grave si l'asset est absent : l'utilisateur pourra ajouter un schéma manuellement
        }
    }

    private fun readCsv(assetName: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        context.assets.open(assetName).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        rows.add(parseCsvLine(line))
                    }
                }
            }
        }
        return rows
    }

    /** Parseur CSV simple (gère les guillemets basiques, suffisant pour nos données). */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result.map { it.trim().trimEnd('\r') }
    }

    private suspend fun importItems() {
        val rows = readCsv("items.csv")
        if (rows.isEmpty()) return
        val header = rows.first()
        val idxUnite = header.indexOf("Unite")
        val idxFamille = header.indexOf("Famille")
        val idxItem = header.indexOf("Item")
        val items = rows.drop(1).mapNotNull { r ->
            if (r.size <= maxOf(idxUnite, idxFamille, idxItem)) return@mapNotNull null
            val unite = r.getOrNull(idxUnite)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val item = r.getOrNull(idxItem)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ItemCatalog(unite = unite, famille = r.getOrNull(idxFamille) ?: "", item = item)
        }
        db.itemCatalogDao().insertAll(items)
    }

    private suspend fun importBrides() {
        val rows = readCsv("brides.csv")
        if (rows.isEmpty()) return
        val header = rows.first()
        fun idx(name: String) = header.indexOf(name)
        val iUnite = idx("Unité")
        val iFamille = idx("Famille")
        val iItem = idx("Item")
        val iRep = idx("Rep")
        val iDesignation = idx("Designation")
        val iDn = idx("DN")
        val iPn = idx("PN")
        val iMatJ = idx("MatiereJ")
        val iRond = idx("Rondelle")
        val iMatB = idx("MatiereB")

        val brides = rows.drop(1).mapNotNull { r ->
            val unite = r.getOrNull(iUnite)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val item = r.getOrNull(iItem)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rep = r.getOrNull(iRep)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrideCatalog(
                unite = unite,
                famille = r.getOrNull(iFamille) ?: "",
                item = item,
                rep = rep,
                designation = r.getOrNull(iDesignation) ?: "",
                dn = r.getOrNull(iDn) ?: "",
                pn = r.getOrNull(iPn) ?: "",
                matiereJoint = r.getOrNull(iMatJ) ?: "",
                rondelle = r.getOrNull(iRond) ?: "",
                matiereBoulon = r.getOrNull(iMatB) ?: ""
            )
        }
        db.brideCatalogDao().insertAll(brides)
    }
}

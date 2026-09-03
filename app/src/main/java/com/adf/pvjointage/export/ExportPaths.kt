package com.adf.pvjointage.export

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dossier d'export commun au PDF et à l'Excel natif : "{Client} - {Chantier} - {Année}",
 * créé si besoin sous files/exports. Permet de retrouver facilement les fichiers d'une
 * campagne donnée une fois sortis de l'appli (explorateur de fichiers, transfert...).
 */
object ExportPaths {

    fun resolveExportDir(context: Context, client: String, chantier: String): File {
        val annee = SimpleDateFormat("yyyy", Locale.FRANCE).format(Date())
        val nomDossier = listOf(client, chantier, annee)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" - ")
            .ifBlank { annee }
        val dir = File(File(context.getExternalFilesDir(null), "exports"), sanitize(nomDossier))
        dir.mkdirs()
        return dir
    }

    /** Remplace les caractères interdits dans un nom de fichier/dossier Android — Client/Lieu/Item sont du texte libre venant de l'Excel importé. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Export" }
}

package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Lit un dossier choisi par l'utilisateur contenant une image par ITEM (le nom du fichier,
 * sans son extension, doit correspondre exactement au code ITEM — ex. "D1153.png") et les
 * associe aux items du catalogue fraîchement importé.
 */
class SchemaFolderImporter(private val context: Context) {

    private val extensionsImages = setOf("png", "jpg", "jpeg", "webp")

    /** [items] : triplets (unite, famille, item) du catalogue tout juste importé depuis le fichier Excel. */
    fun importSchemas(treeUri: Uri, items: List<Triple<String, String, String>>): List<ItemSchema> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw ExcelImporter.ExcelImportException("Impossible d'ouvrir le dossier de schémas sélectionné.")

        val destDir = (context.getExternalFilesDir("schemas") ?: context.filesDir).apply { mkdirs() }
        val schemas = mutableListOf<ItemSchema>()

        for (file in root.listFiles()) {
            if (!file.isFile) continue
            val name = file.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in extensionsImages) continue
            val itemCode = name.substringBeforeLast('.').trim()
            if (itemCode.isEmpty()) continue

            val correspondances = items.filter { it.third.equals(itemCode, ignoreCase = true) }
            if (correspondances.isEmpty()) continue

            val destFile = File(destDir, "schema_${itemCode}.$ext")
            val copied = context.contentResolver.openInputStream(file.uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) continue

            correspondances.forEach { (unite, famille, item) ->
                schemas.add(ItemSchema(unite = unite, famille = famille, item = item, filePath = destFile.absolutePath))
            }
        }
        return schemas
    }
}

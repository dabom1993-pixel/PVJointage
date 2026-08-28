package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Lit l'onglet "1-Trame" d'un classeur Excel (.xlsx / .xlsm) choisi par l'utilisateur et en
 * extrait le catalogue des brides (Unité, Famille, Item, Rep., Désignation, DN, PN, MatièreJ,
 * Rondelle, MatièreB).
 *
 * Un fichier .xlsx/.xlsm est une simple archive ZIP contenant des fichiers XML (format
 * OOXML) : pas besoin de bibliothèque externe, on lit directement ce ZIP.
 */
class ExcelImporter(private val context: Context) {

    private val nomOnglet = "1-Trame"
    private val colonnesRequises = listOf(
        "unite", "famille", "item", "rep", "designation", "dn", "pn", "matierej", "rondelle", "matiereb"
    )

    class ExcelImportException(message: String) : Exception(message)

    /** Retourne le catalogue des brides lu depuis l'onglet "1-Trame" du fichier pointé par [uri]. */
    fun parseTrame(uri: Uri): List<BrideCatalog> {
        val tempFile = copyToTempFile(uri)
        try {
            ZipFile(tempFile).use { zip ->
                val sheetPath = findSheetPath(zip, nomOnglet)
                    ?: throw ExcelImportException("Onglet \"$nomOnglet\" introuvable dans le fichier.")
                val sharedStrings = readSharedStrings(zip)
                return readBrides(zip, sheetPath, sharedStrings)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun copyToTempFile(uri: Uri): File {
        val tempFile = File.createTempFile("import_", ".xlsm", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ExcelImportException("Impossible d'ouvrir le fichier sélectionné.")
        return tempFile
    }

    /** Résout xl/workbook.xml + xl/_rels/workbook.xml.rels pour trouver le fichier XML de l'onglet demandé. */
    private fun findSheetPath(zip: ZipFile, sheetName: String): String? {
        val workbookEntry = zip.getEntry("xl/workbook.xml") ?: return null
        var relId: String? = null
        zip.getInputStream(workbookEntry).use { input ->
            val parser = newParser(input)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null && name.trim().equals(sheetName, ignoreCase = true)) {
                        relId = parser.getAttributeValue(null, "r:id")
                    }
                }
                event = parser.next()
            }
        }
        val id = relId ?: return null

        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return null
        var target: String? = null
        zip.getInputStream(relsEntry).use { input ->
            val parser = newParser(input)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    if (parser.getAttributeValue(null, "Id") == id) {
                        target = parser.getAttributeValue(null, "Target")
                    }
                }
                event = parser.next()
            }
        }
        return target?.let { "xl/${it.removePrefix("/")}" }
    }

    /** Lit xl/sharedStrings.xml : les cellules texte n'embarquent qu'un index dans cette table. */
    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val result = mutableListOf<String>()
        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var event = parser.eventType
            var inSi = false
            val current = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "si") { inSi = true; current.clear() }
                    XmlPullParser.TEXT -> if (inSi) current.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "si") { result.add(current.toString()); inSi = false }
                }
                event = parser.next()
            }
        }
        return result
    }

    private fun readBrides(zip: ZipFile, sheetPath: String, sharedStrings: List<String>): List<BrideCatalog> {
        val entry = zip.getEntry(sheetPath)
            ?: throw ExcelImportException("Feuille \"$sheetPath\" introuvable dans le fichier.")

        val brides = mutableListOf<BrideCatalog>()
        var colonnes: Map<String, String>? = null // nom de colonne normalisé -> lettre de colonne

        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var event = parser.eventType

            var inRow = false
            var rowValues = linkedMapOf<String, String>() // lettre de colonne -> valeur

            var currentCellRef: String? = null
            var currentCellType: String? = null
            var readingValue = false
            val valueBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> { inRow = true; rowValues = linkedMapOf() }
                        "c" -> {
                            currentCellRef = parser.getAttributeValue(null, "r")
                            currentCellType = parser.getAttributeValue(null, "t")
                        }
                        "v" -> { readingValue = true; valueBuilder.clear() }
                    }
                    XmlPullParser.TEXT -> if (readingValue) valueBuilder.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v" -> {
                            readingValue = false
                            val ref = currentCellRef
                            if (ref != null) {
                                val colLetters = ref.takeWhile { it.isLetter() }
                                val raw = valueBuilder.toString()
                                val resolved = if (currentCellType == "s") {
                                    raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                } else raw
                                rowValues[colLetters] = resolved
                            }
                        }
                        "c" -> { currentCellRef = null; currentCellType = null }
                        "row" -> {
                            inRow = false
                            if (colonnes == null) {
                                val header = detectHeader(rowValues)
                                if (header != null) colonnes = header
                            } else {
                                val cols = colonnes!!
                                val unite = rowValues[cols["unite"]]?.trim().orEmpty()
                                val item = rowValues[cols["item"]]?.trim().orEmpty()
                                val rep = rowValues[cols["rep"]]?.trim().orEmpty()
                                if (unite.isNotEmpty() && item.isNotEmpty() && rep.isNotEmpty()) {
                                    brides.add(
                                        BrideCatalog(
                                            unite = unite,
                                            famille = rowValues[cols["famille"]]?.trim().orEmpty(),
                                            item = item,
                                            rep = rep,
                                            designation = rowValues[cols["designation"]]?.trim().orEmpty(),
                                            dn = rowValues[cols["dn"]]?.trim().orEmpty(),
                                            pn = rowValues[cols["pn"]]?.trim().orEmpty(),
                                            matiereJoint = rowValues[cols["matierej"]]?.trim().orEmpty(),
                                            rondelle = rowValues[cols["rondelle"]]?.trim().orEmpty(),
                                            matiereBoulon = rowValues[cols["matiereb"]]?.trim().orEmpty()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }

        if (colonnes == null) {
            throw ExcelImportException("Ligne d'en-têtes introuvable dans l'onglet \"$nomOnglet\" (colonne \"Unité\" attendue).")
        }
        return brides
    }

    /** Une ligne est la ligne d'en-têtes si l'une de ses cellules normalisée vaut "unite". */
    private fun detectHeader(rowValues: Map<String, String>): Map<String, String>? {
        val normalizedToCol = rowValues.mapNotNull { (col, value) ->
            val n = normalize(value)
            if (n.isNotEmpty()) n to col else null
        }.toMap()
        if (!normalizedToCol.containsKey("unite")) return null
        if (colonnesRequises.any { it !in normalizedToCol }) return null
        return normalizedToCol
    }

    private fun normalize(s: String): String {
        val sansAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return sansAccents.lowercase(Locale.FRANCE).replace(Regex("[^a-z0-9]"), "")
    }

    private fun newParser(input: java.io.InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        return parser
    }
}

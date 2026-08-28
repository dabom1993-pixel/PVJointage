package com.adf.pvjointage.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Utilitaires bas niveau communs pour lire/écrire un classeur Excel (.xlsx/.xlsm — une archive
 * ZIP contenant des fichiers XML au format OOXML), partagés par [ExcelImporter] (lecture) et
 * [ExcelNativeExporter] (écriture).
 */
internal object ExcelXmlUtils {

    fun newParser(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        return parser
    }

    /** Noms des onglets du classeur, dans leur ordre d'origine. */
    fun readSheetNames(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
        val names = mutableListOf<String>()
        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    parser.getAttributeValue(null, "name")?.let { names.add(it) }
                }
                event = parser.next()
            }
        }
        return names
    }

    /** Résout xl/workbook.xml + xl/_rels/workbook.xml.rels pour trouver le fichier XML de l'onglet demandé. */
    fun findSheetPath(zip: ZipFile, sheetName: String): String? {
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
    fun readSharedStrings(zip: ZipFile): List<String> {
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

    fun normalize(s: String): String {
        val sansAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return sansAccents.lowercase(Locale.FRANCE).replace(Regex("[^a-z0-9]"), "")
    }

    fun columnToIndex(col: String): Int {
        var result = 0
        for (c in col) result = result * 26 + (c - 'A' + 1)
        return result
    }

    fun indexToColumn(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i > 0) {
            val rem = (i - 1) % 26
            sb.insert(0, ('A' + rem))
            i = (i - 1) / 26
        }
        return sb.toString()
    }
}

package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Lit un classeur Excel (.xlsx / .xlsm) choisi par l'utilisateur : liste ses onglets, puis
 * extrait le catalogue des brides (Unité, Famille, Item, Rep., Désignation, DN, PN, MatièreJ,
 * Rondelle, MatièreB) ainsi que le Client / Lieu depuis l'onglet choisi par l'utilisateur.
 *
 * Un fichier .xlsx/.xlsm est une simple archive ZIP contenant des fichiers XML (format
 * OOXML) : pas besoin de bibliothèque externe, on lit directement ce ZIP (voir [ExcelXmlUtils]).
 */
class ExcelImporter(private val context: Context) {

    private val colonnesRequises = listOf(
        "unite", "famille", "item", "rep", "designation", "dn", "pn", "matierej", "rondelle", "matiereb"
    )
    private val libellesClient = setOf("client", "nomclient", "nomduclient")
    private val libellesLieu = setOf("lieu")

    class ExcelImportException(message: String) : Exception(message)

    data class TrameImportResult(
        val brides: List<BrideCatalog>,
        val client: String,
        val lieu: String
    )

    /** Copie le fichier choisi en local et retourne un accès à son contenu (onglets, données). */
    fun open(uri: Uri): OpenWorkbook = OpenWorkbook(copyToTempFile(uri))

    inner class OpenWorkbook(val file: File) {

        /** Noms des onglets du classeur, dans leur ordre d'origine. */
        fun listSheetNames(): List<String> = ZipFile(file).use { zip -> ExcelXmlUtils.readSheetNames(zip) }

        /** Lit le catalogue de brides + Client/Lieu depuis l'onglet [sheetName]. */
        fun parseSheet(sheetName: String): TrameImportResult {
            ZipFile(file).use { zip ->
                val sheetPath = ExcelXmlUtils.findSheetPath(zip, sheetName)
                    ?: throw ExcelImportException("Onglet \"$sheetName\" introuvable dans le fichier.")
                val sharedStrings = ExcelXmlUtils.readSharedStrings(zip)
                return readSheetData(zip, sheetPath, sharedStrings, sheetName)
            }
        }

        /** À appeler une fois l'import terminé (fichier temporaire local à supprimer). */
        fun close() {
            file.delete()
        }
    }

    private fun copyToTempFile(uri: Uri): File {
        val tempFile = File.createTempFile("import_", ".xlsm", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ExcelImportException("Impossible d'ouvrir le fichier sélectionné.")
        return tempFile
    }

    private fun readSheetData(zip: ZipFile, sheetPath: String, sharedStrings: List<String>, sheetName: String): TrameImportResult {
        val entry = zip.getEntry(sheetPath)
            ?: throw ExcelImportException("Feuille \"$sheetPath\" introuvable dans le fichier.")

        val brides = mutableListOf<BrideCatalog>()
        var colonnes: Map<String, String>? = null // nom de colonne normalisé -> lettre de colonne
        var client = ""
        var lieu = ""

        zip.getInputStream(entry).use { input ->
            val parser = ExcelXmlUtils.newParser(input)
            var event = parser.eventType

            var rowValues = linkedMapOf<String, String>() // lettre de colonne -> valeur

            var currentCellRef: String? = null
            var currentCellType: String? = null
            var readingValue = false
            val valueBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> { rowValues = linkedMapOf() }
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
                            if (colonnes == null) {
                                if (client.isEmpty()) scanForLabel(rowValues, libellesClient)?.let { client = it }
                                if (lieu.isEmpty()) scanForLabel(rowValues, libellesLieu)?.let { lieu = it }

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
                                            matiereBoulon = rowValues[cols["matiereb"]]?.trim().orEmpty(),
                                            // Colonnes optionnelles : absentes des fichiers plus anciens.
                                            longueurBoulon = rowValues[cols["lgb"]]?.trim().orEmpty(),
                                            diametreBoulon = rowValues[cols["diamb"]]?.trim().orEmpty(),
                                            neufBoulon = rowValues[cols["neufb"]]?.trim().orEmpty()
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
            throw ExcelImportException("Ligne d'en-têtes introuvable dans l'onglet \"$sheetName\" (colonne \"Unité\" attendue).")
        }
        return TrameImportResult(brides, client, lieu)
    }

    /** Cherche une cellule dont le texte normalisé correspond à l'un des [labels] et retourne la valeur de la cellule juste à droite. */
    private fun scanForLabel(rowValues: Map<String, String>, labels: Set<String>): String? {
        for ((col, value) in rowValues) {
            if (ExcelXmlUtils.normalize(value) in labels) {
                val nextCol = ExcelXmlUtils.indexToColumn(ExcelXmlUtils.columnToIndex(col) + 1)
                val v = rowValues[nextCol]?.trim()
                if (!v.isNullOrEmpty()) return v
            }
        }
        return null
    }

    /** Une ligne est la ligne d'en-têtes si l'une de ses cellules normalisée vaut "unite". */
    private fun detectHeader(rowValues: Map<String, String>): Map<String, String>? {
        val normalizedToCol = rowValues.mapNotNull { (col, value) ->
            val n = ExcelXmlUtils.normalize(value)
            if (n.isNotEmpty()) n to col else null
        }.toMap()
        if (!normalizedToCol.containsKey("unite")) return null
        if (colonnesRequises.any { it !in normalizedToCol }) return null
        return normalizedToCol
    }
}

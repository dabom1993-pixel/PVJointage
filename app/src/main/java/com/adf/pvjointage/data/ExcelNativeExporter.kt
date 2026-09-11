package com.adf.pvjointage.data

import android.content.Context
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Réécrit directement le classeur Excel de référence (celui importé via "Importer") : remplit
 * les colonnes de contrôle (O/N/A saisis sur la tablette) de chaque bride, tous items
 * confondus, sans passer par un fichier CSV intermédiaire. Les brides ajoutées manuellement sur
 * la tablette (absentes de l'Excel importé, voir MainActivity.showAjouterBrideDialog) sont
 * insérées comme nouvelles lignes juste à la suite des lignes existantes de leur item, avec
 * toutes leurs données de référence saisies (voir [insertBrideRows]).
 *
 * Les colonnes sont retrouvées par leur libellé (Mise/Nom/MatJ/Dim/...), pas par une lettre
 * fixe : l'onglet source a déjà changé de disposition une fois (2 colonnes "LgB"/"DiamB"
 * insérées avant "MatièreB", décalant tout ce qui suit) et retrouvera sans doute d'autres
 * colonnes à l'avenir.
 *
 * Le fichier de référence lui-même n'est jamais modifié : chaque export en repart et produit
 * une nouvelle copie à jour (mêmes macros/mise en forme, seules les cellules de contrôle changent,
 * plus les éventuelles lignes insérées). Limite connue : la zone d'impression et la plage du
 * tableau structuré Excel (définies dans workbook.xml / xl/tables), non modifiées ici, ne
 * s'étendent pas automatiquement aux lignes insérées — à ajuster manuellement dans Excel si besoin.
 */
class ExcelNativeExporter(private val context: Context) {

    class NativeExportException(message: String) : Exception(message)

    /** [shortKey] = libellé normalisé de la colonne dans la ligne d'en-têtes ("Mise", "Nom", ...). */
    private enum class InspField(val shortKey: String) {
        ETI_MISE("mise"), ETI_NOM("nom"), JOINT_MATIERE("matj"), JOINT_DIM("dim"), JOINT_ASPECT("visu"),
        BOULON_NEUVES("neuve"), BOULON_RONDELLES("rond"), BOULON_EQUILIBRAGE("equi"), BOULON_GRAISSAGE("grais"),
        BOULON_LGDIAM("lgdiam"), BOULON_MATIERE("matb"), ASSEMBLAGE_PARA("para"), ASSEMBLAGE_EXC("exc")
    }

    private fun valueFor(field: InspField, insp: InspectionResult): String? {
        val raw = when (field) {
            InspField.ETI_MISE -> insp.etiMiseSerree
            InspField.ETI_NOM -> insp.etiNomDateLisible
            InspField.JOINT_MATIERE -> insp.jointMatiereConforme
            InspField.JOINT_DIM -> insp.jointDimensionCentrage
            InspField.JOINT_ASPECT -> insp.jointAspectNeuf
            InspField.BOULON_NEUVES -> insp.boulonNeuves
            InspField.BOULON_RONDELLES -> insp.boulonRondelles
            InspField.BOULON_EQUILIBRAGE -> insp.boulonEquilibrage
            InspField.BOULON_GRAISSAGE -> insp.boulonGraissage
            InspField.BOULON_LGDIAM -> insp.boulonLongueurDiametre
            InspField.BOULON_MATIERE -> insp.boulonMatiere
            InspField.ASSEMBLAGE_PARA -> insp.assemblageParallelisme
            InspField.ASSEMBLAGE_EXC -> insp.assemblageExcentration
        }
        return raw.takeIf { it.isNotBlank() }
    }

    /** Dossier "{Client} - {Chantier} - {Année}" (voir [com.adf.pvjointage.export.ExportPaths]) — même convention que l'export PDF. */
    private fun exportDir(client: String, chantier: String): File =
        com.adf.pvjointage.export.ExportPaths.resolveExportDir(context, client, chantier)

    /**
     * [brides] : catalogue complet (tous items). [inspections] : résultats indexés par
     * "unite|famille|item|rep". Retourne le chemin du fichier .xlsm généré.
     */
    fun export(
        referenceFile: File, sheetName: String, brides: List<BrideCatalog>, inspections: Map<String, InspectionResult>,
        client: String, chantier: String
    ): String {
        if (!referenceFile.exists()) {
            throw NativeExportException("Aucun fichier Excel de référence : importez d'abord un fichier via \"Importer\".")
        }

        val sheetPath: String
        val sharedStrings: List<String>
        val originalSheetXml: ByteArray

        ZipFile(referenceFile).use { zip ->
            sheetPath = ExcelXmlUtils.findSheetPath(zip, sheetName)
                ?: throw NativeExportException("Onglet \"$sheetName\" introuvable dans le fichier de référence.")
            sharedStrings = ExcelXmlUtils.readSharedStrings(zip)
            val entry = zip.getEntry(sheetPath) ?: throw NativeExportException("Feuille \"$sheetPath\" introuvable dans le fichier de référence.")
            originalSheetXml = zip.getInputStream(entry).use { it.readBytes() }
        }

        val updatedSheetXml = updateSheetXml(originalSheetXml, sharedStrings, brides, inspections)

        val fileName = "PV_Jointage_${System.currentTimeMillis()}.xlsm"
        val outFile = File(exportDir(client, chantier), fileName)
        rewriteZipReplacingEntry(referenceFile, sheetPath, updatedSheetXml, outFile)
        return outFile.absolutePath
    }

    /** [shortKey] normalisé -> valeur de référence (colonnes catalogue, pas contrôle) d'une bride ajoutée manuellement. */
    private fun referenceValueFor(shortKey: String, b: BrideCatalog): String? {
        val raw = when (shortKey) {
            "unite" -> b.unite
            "famille" -> b.famille
            "item" -> b.item
            "rep" -> b.rep
            "designation" -> b.designation
            "dn" -> b.dn
            "pn" -> b.pn
            "matierej" -> b.matiereJoint
            "rondelle" -> b.rondelle
            "lgb" -> b.longueurBoulon
            "diamb" -> b.diametreBoulon
            "neufb" -> b.neufBoulon
            "matiereb" -> b.matiereBoulon
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    private fun updateSheetXml(
        xmlBytes: ByteArray, sharedStrings: List<String>, brides: List<BrideCatalog>, inspections: Map<String, InspectionResult>
    ): ByteArray {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc: Document = xmlBytes.inputStream().use { builder.parse(it) }

        val sheetDataList = doc.getElementsByTagName("sheetData")
        if (sheetDataList.length == 0) throw NativeExportException("Structure de feuille inattendue (sheetData introuvable).")
        val sheetData = sheetDataList.item(0) as Element
        val rowNodes = sheetData.getElementsByTagName("row")

        var colonnes: Map<String, String>? = null // nom normalisé -> lettre de colonne (unite/famille/item/rep)
        val requis = listOf("unite", "famille", "item", "rep")

        // Repères déjà présents dans la feuille (pour repérer les brides ajoutées manuellement,
        // absentes de l'Excel importé) et dernière ligne connue de chaque item (point d'insertion).
        val foundKeys = mutableSetOf<String>()
        val lastRowNumForItem = mutableMapOf<String, Int>()

        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as Element
            val cellsByCol = mutableMapOf<String, Element>()
            val children = row.getElementsByTagName("c")
            for (j in 0 until children.length) {
                val c = children.item(j) as Element
                val ref = c.getAttribute("r")
                if (ref.isNotEmpty()) cellsByCol[ref.takeWhile { it.isLetter() }] = c
            }
            if (cellsByCol.isEmpty()) continue

            val cols = colonnes
            if (cols == null) {
                val normalizedToCol = mutableMapOf<String, String>()
                for ((col, cell) in cellsByCol) {
                    val n = ExcelXmlUtils.normalize(cellText(cell, sharedStrings))
                    if (n.isNotEmpty()) normalizedToCol[n] = col
                }
                if (requis.all { it in normalizedToCol }) colonnes = normalizedToCol
                continue
            }

            val unite = cellsByCol[cols["unite"]]?.let { cellText(it, sharedStrings) }?.trim().orEmpty()
            val famille = cellsByCol[cols["famille"]]?.let { cellText(it, sharedStrings) }?.trim().orEmpty()
            val item = cellsByCol[cols["item"]]?.let { cellText(it, sharedStrings) }?.trim().orEmpty()
            val rep = cellsByCol[cols["rep"]]?.let { cellText(it, sharedStrings) }?.trim().orEmpty()
            if (unite.isEmpty() || item.isEmpty() || rep.isEmpty()) continue

            val rowNum = row.getAttribute("r").toIntOrNull()
            if (rowNum != null) {
                foundKeys.add("$unite|$famille|$item|$rep")
                val itemKey = "$unite|$famille|$item"
                if (rowNum > (lastRowNumForItem[itemKey] ?: -1)) lastRowNumForItem[itemKey] = rowNum
            }

            val insp = inspections["$unite|$famille|$item|$rep"] ?: continue

            for (field in InspField.values()) {
                val colLetter = cols[field.shortKey] ?: continue // libellé absent de cet onglet : on l'ignore
                val value = valueFor(field, insp) ?: continue
                setInlineStringCell(doc, row, cellsByCol, colLetter, value)
            }
        }

        // Brides ajoutées manuellement sur la tablette (bouton "+ Ajouter une bride"), absentes de
        // l'Excel importé : insérées à la suite des lignes existantes de leur item, avec toutes
        // leurs données de référence saisies + les contrôles déjà réalisés le cas échéant.
        val cols = colonnes
        if (cols != null) {
            val missing = brides.filter { b -> "${b.unite}|${b.famille}|${b.item}|${b.rep}" !in foundKeys }
            if (missing.isNotEmpty()) {
                val parItem = missing.groupBy { "${it.unite}|${it.famille}|${it.item}" }
                val pointsInsertion = parItem.keys.mapNotNull { key -> lastRowNumForItem[key]?.let { key to it } }
                    .sortedByDescending { it.second } // du bas de la feuille vers le haut : chaque insertion ne perturbe pas les suivantes (déjà traitées, plus haut).
                for ((itemKey, apresLigne) in pointsInsertion) {
                    insertBrideRows(doc, sheetData, cols, apresLigne, parItem.getValue(itemKey), inspections)
                }
            }
        }

        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    /**
     * Insère une nouvelle ligne par bride de [nouvellesBrides] juste après la ligne [apresLigne]
     * (dernière ligne existante de leur item), en décalant vers le bas toutes les lignes situées
     * après ce point (numéro de ligne + référence de chaque cellule).
     */
    private fun insertBrideRows(
        doc: Document, sheetData: Element, cols: Map<String, String>, apresLigne: Int,
        nouvellesBrides: List<BrideCatalog>, inspections: Map<String, InspectionResult>
    ) {
        val n = nouvellesBrides.size

        // 1. Décale toutes les lignes situées après le point d'insertion.
        val toutesLesLignes = run {
            val nodes = sheetData.getElementsByTagName("row")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        for (row in toutesLesLignes) {
            val ancienNum = row.getAttribute("r").toIntOrNull() ?: continue
            if (ancienNum <= apresLigne) continue
            val nouveauNum = ancienNum + n
            row.setAttribute("r", nouveauNum.toString())
            val cellules = row.getElementsByTagName("c")
            for (j in 0 until cellules.length) {
                val cell = cellules.item(j) as Element
                val ref = cell.getAttribute("r")
                val lettres = ref.takeWhile { it.isLetter() }
                if (lettres.isNotEmpty()) cell.setAttribute("r", "$lettres$nouveauNum")
            }
        }

        // 2. Repère la ligne juste après (désormais renumérotée) pour y insérer les nouvelles avant elle.
        val ancreNum = apresLigne + n + 1
        val ancre = run {
            val nodes = sheetData.getElementsByTagName("row")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.firstOrNull { it.getAttribute("r").toIntOrNull() == ancreNum }

        // 3. Crée une ligne par nouvelle bride, avec ses données de référence + contrôles déjà saisis.
        nouvellesBrides.forEachIndexed { index, bride ->
            val numeroLigne = apresLigne + 1 + index
            val nouvelleLigne = doc.createElement("row")
            nouvelleLigne.setAttribute("r", numeroLigne.toString())
            val cellsByCol = mutableMapOf<String, Element>()

            val referencesAEcrire = listOf(
                "unite", "famille", "item", "rep", "designation", "dn", "pn",
                "matierej", "rondelle", "lgb", "diamb", "neufb", "matiereb"
            )
            for (shortKey in referencesAEcrire) {
                val colLetter = cols[shortKey] ?: continue
                val value = referenceValueFor(shortKey, bride) ?: continue
                setInlineStringCell(doc, nouvelleLigne, cellsByCol, colLetter, value)
            }

            val insp = inspections["${bride.unite}|${bride.famille}|${bride.item}|${bride.rep}"]
            if (insp != null) {
                for (field in InspField.values()) {
                    val colLetter = cols[field.shortKey] ?: continue
                    val value = valueFor(field, insp) ?: continue
                    setInlineStringCell(doc, nouvelleLigne, cellsByCol, colLetter, value)
                }
            }

            if (ancre != null) sheetData.insertBefore(nouvelleLigne, ancre) else sheetData.appendChild(nouvelleLigne)
        }
    }

    private fun cellText(cell: Element, sharedStrings: List<String>): String {
        val vNodes = cell.getElementsByTagName("v")
        if (vNodes.length == 0) return ""
        val raw = vNodes.item(0).textContent ?: ""
        return if (cell.getAttribute("t") == "s") {
            raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
        } else raw
    }

    /** Écrit [value] comme chaîne "inline" (pas besoin de toucher la table sharedStrings.xml). */
    private fun setInlineStringCell(doc: Document, row: Element, cellsByCol: MutableMap<String, Element>, colLetter: String, value: String) {
        val existing = cellsByCol[colLetter]
        val cell: Element = if (existing != null) {
            while (existing.hasChildNodes()) existing.removeChild(existing.firstChild)
            existing
        } else {
            val newCell = doc.createElement("c")
            newCell.setAttribute("r", "$colLetter${row.getAttribute("r")}")
            insertCellInOrder(row, newCell, colLetter)
            cellsByCol[colLetter] = newCell
            newCell
        }
        cell.setAttribute("t", "inlineStr")
        val isEl = doc.createElement("is")
        val tEl = doc.createElement("t")
        tEl.appendChild(doc.createTextNode(value))
        isEl.appendChild(tEl)
        cell.appendChild(isEl)
    }

    private fun insertCellInOrder(row: Element, newCell: Element, colLetter: String) {
        val targetIndex = ExcelXmlUtils.columnToIndex(colLetter)
        val children = row.getElementsByTagName("c")
        var refNode: Element? = null
        for (i in 0 until children.length) {
            val c = children.item(i) as Element
            val ref = c.getAttribute("r").takeWhile { it.isLetter() }
            if (ref.isNotEmpty() && ExcelXmlUtils.columnToIndex(ref) > targetIndex) {
                refNode = c
                break
            }
        }
        if (refNode != null) row.insertBefore(newCell, refNode) else row.appendChild(newCell)
    }

    /** Copie le zip d'origine octet à octet, en remplaçant uniquement [targetEntryName] par [newContent]. */
    private fun rewriteZipReplacingEntry(sourceZip: File, targetEntryName: String, newContent: ByteArray, destFile: File) {
        ZipInputStream(sourceZip.inputStream().buffered()).use { zin ->
            ZipOutputStream(FileOutputStream(destFile).buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    zout.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == targetEntryName) {
                        zout.write(newContent)
                    } else {
                        zin.copyTo(zout)
                    }
                    zout.closeEntry()
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
    }
}

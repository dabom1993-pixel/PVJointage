package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Etat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "ITEM12" -> "ITEM12" si jamais révisé, "ITEM12-R1" à partir de la 1ère révision post-export. */
fun itemDisplayLabel(item: String, revision: Int): String = if (revision > 0) "$item-R$revision" else item

class Repository(private val appContext: Context) {
    private val db = AppDatabase.getInstance(appContext)
    private val seedImporter = SeedImporter(appContext.applicationContext, db)
    private val prefs = AppPrefs(appContext)

    suspend fun ensureSeedData() = seedImporter.importIfNeeded()

    data class ImportResult(val brideCount: Int, val schemaCount: Int)

    /** Copie localement le fichier Excel choisi et permet d'en lister/lire les onglets. */
    suspend fun openExcelWorkbook(uri: Uri): ExcelImporter.OpenWorkbook = withContext(Dispatchers.IO) {
        ExcelImporter(appContext).open(uri)
    }

    suspend fun listExcelSheets(workbook: ExcelImporter.OpenWorkbook): List<String> = withContext(Dispatchers.IO) {
        workbook.listSheetNames()
    }

    /**
     * Réimporte le catalogue des brides + Client/Lieu depuis l'onglet [sheetName] choisi par
     * l'utilisateur, en écrasant le catalogue existant (brides + items dérivés). Si
     * [imagesTreeUri] est fourni, importe aussi les schémas depuis ce dossier (une image par
     * ITEM, nommée par son code), en écrasant les schémas existants.
     */
    suspend fun importFromExcelAndImages(workbook: ExcelImporter.OpenWorkbook, sheetName: String, imagesTreeUri: Uri?): ImportResult {
        val parsed = withContext(Dispatchers.IO) { workbook.parseSheet(sheetName) }
        val items = parsed.brides.map { Triple(it.unite, it.famille, it.item) }.distinct()

        val schemas = imagesTreeUri?.let { SchemaFolderImporter(appContext).importSchemas(it, items) } ?: emptyList()

        db.withTransaction {
            db.brideCatalogDao().deleteAll()
            db.brideCatalogDao().insertAll(parsed.brides)
            db.itemCatalogDao().deleteAll()
            db.itemCatalogDao().insertAll(items.map { ItemCatalog(unite = it.first, famille = it.second, item = it.third) })
            if (imagesTreeUri != null) {
                db.itemSchemaDao().deleteAll()
                db.itemSchemaDao().insertAll(schemas)
            }
            // Client / Lieu proviennent désormais du fichier Excel importé (non saisis dans l'app).
            // La sélection Unité/Famille/Item mémorisée est aussi remise à vide ici (pas seulement
            // côté UI) : sinon, comme le catalogue peut réutiliser les mêmes noms d'un import à
            // l'autre, MainActivity pouvait restaurer une ancienne sélection valide en apparence
            // (même Unité/Famille) mais dont l'Item ne correspondait plus à rien dans les
            // nouvelles données, laissant la liste des items vide.
            val current = db.pvHeaderDao().getHeaderOnce() ?: PvHeader()
            db.pvHeaderDao().save(
                current.copy(
                    client = parsed.client, lieu = parsed.lieu, date = today(),
                    uniteSelectionnee = "", familleSelectionnee = "", itemSelectionne = ""
                )
            )
        }

        // Conserve une copie locale du classeur importé : c'est elle que l'export Excel
        // "natif" réutilisera plus tard pour remplir les colonnes K à W.
        withContext(Dispatchers.IO) { workbook.file.copyTo(referenceExcelFile(), overwrite = true) }
        prefs.lastImportSheetName = sheetName

        return ImportResult(parsed.brides.size, schemas.size)
    }

    /**
     * Réécrit le classeur Excel importé (colonnes K à W) avec les contrôles saisis sur la
     * tablette, pour toutes les brides de tous les items. Ne crée pas de CSV : le résultat est
     * une nouvelle copie du fichier .xlsm d'origine, à jour. Retourne le chemin du fichier généré.
     */
    suspend fun exportNativeExcel(): String = withContext(Dispatchers.IO) {
        val sheetName = prefs.lastImportSheetName
            ?: throw ExcelImporter.ExcelImportException("Importez d'abord un fichier Excel avant d'exporter.")
        val header = db.pvHeaderDao().getHeaderOnce()
        val brides = db.brideCatalogDao().getAllOnce()
        val inspections = db.inspectionResultDao().getAllOnce()
            .associateBy { "${it.unite}|${it.famille}|${it.item}|${it.rep}" }
        ExcelNativeExporter(appContext).export(
            referenceExcelFile(), sheetName, brides, inspections,
            client = header?.client.orEmpty(), chantier = header?.lieu.orEmpty()
        )
    }

    private fun referenceExcelFile(): File {
        val dir = File(appContext.getExternalFilesDir(null), "reference")
        dir.mkdirs()
        return File(dir, "classeur_reference.xlsm")
    }

    data class CatalogueEntry(
        val unite: String, val famille: String, val item: String, val complete: Boolean, val revision: Int = 0,
        // true dès qu'un export PDF a déjà eu lieu pour cet item (peu importe la révision).
        val exported: Boolean = false
    ) {
        /** Libellé à afficher (filtres/catalogue/impression) : "$item" ou "$item-R$revision" si révisé. */
        val displayItem: String get() = itemDisplayLabel(item, revision)
    }

    /**
     * Vue d'ensemble Unité/Famille/Item pour la fenêtre "Catalogue" : un item est considéré
     * "complet" (rempli à 100 %) si toutes ses brides ont un statut global déterminé
     * (CONFORME ou NON CONFORME), c'est-à-dire plus aucune case en attente.
     */
    suspend fun getCatalogueOverview(): List<CatalogueEntry> = withContext(Dispatchers.IO) {
        val items = db.itemCatalogDao().getAllOnce()
        val bridesByItem = db.brideCatalogDao().getAllOnce().groupBy { Triple(it.unite, it.famille, it.item) }
        val inspectionsByKey = db.inspectionResultDao().getAllOnce()
            .associateBy { "${it.unite}|${it.famille}|${it.item}|${it.rep}" }
        val revisionsByItem = db.itemRevisionDao().getAllOnce()
            .associateBy { Triple(it.unite, it.famille, it.item) }

        items.map { ic ->
            val brides = bridesByItem[Triple(ic.unite, ic.famille, ic.item)].orEmpty()
            val complete = brides.isNotEmpty() && brides.all { b ->
                val insp = inspectionsByKey["${ic.unite}|${ic.famille}|${ic.item}|${b.rep}"]
                insp != null && globalConformite(insp) != Conformite.EN_ATTENTE
            }
            val itemRevision = revisionsByItem[Triple(ic.unite, ic.famille, ic.item)]
            val revision = itemRevision?.revision ?: 0
            val exported = (itemRevision?.exportedRevision ?: -1) >= 0
            CatalogueEntry(ic.unite, ic.famille, ic.item, complete, revision, exported)
        }
    }

    private fun globalConformite(insp: InspectionResult): Conformite {
        val etiquette = ConformiteCalculator.etiquette(Etat.fromCode(insp.etiMiseSerree), Etat.fromCode(insp.etiNomDateLisible))
        val joint = ConformiteCalculator.joint(Etat.fromCode(insp.jointMatiereConforme), Etat.fromCode(insp.jointDimensionCentrage), Etat.fromCode(insp.jointAspectNeuf))
        val boulonnerie = ConformiteCalculator.boulonnerie(
            Etat.fromCode(insp.boulonNeuves), Etat.fromCode(insp.boulonRondelles), Etat.fromCode(insp.boulonEquilibrage),
            Etat.fromCode(insp.boulonGraissage), Etat.fromCode(insp.boulonLongueurDiametre), Etat.fromCode(insp.boulonMatiere)
        )
        val assemblage = ConformiteCalculator.assemblage(Etat.fromCode(insp.assemblageParallelisme), Etat.fromCode(insp.assemblageExcentration))
        return ConformiteCalculator.global(etiquette, joint, boulonnerie, assemblage)
    }

    // Catalogue
    fun getUnites(): Flow<List<String>> = db.itemCatalogDao().getUnites()
    fun getFamilles(unite: String): Flow<List<String>> = db.itemCatalogDao().getFamilles(unite)
    fun getItems(unite: String, famille: String): Flow<List<String>> = db.itemCatalogDao().getItems(unite, famille)
    fun getBrides(unite: String, famille: String, item: String): Flow<List<BrideCatalog>> =
        db.brideCatalogDao().getBrides(unite, famille, item)

    // En-tête PV
    fun getHeader(): Flow<PvHeader?> = db.pvHeaderDao().getHeader()

    /** La Date est recalculée automatiquement à chaque enregistrement (jamais saisie à la main). */
    suspend fun saveHeader(header: PvHeader) = db.pvHeaderDao().save(header.copy(date = today()))

    // Résultats de contrôle
    fun getInspectionsForItem(unite: String, famille: String, item: String): Flow<List<InspectionResult>> =
        db.inspectionResultDao().getForItem(unite, famille, item)

    suspend fun getInspectionForBride(unite: String, famille: String, item: String, rep: String): InspectionResult? =
        db.inspectionResultDao().getForBride(unite, famille, item, rep)

    suspend fun saveInspection(result: InspectionResult) {
        db.inspectionResultDao().upsert(result)
        touchDate()
        touchItemRevision(result.unite, result.famille, result.item)
    }

    // Photos
    fun getPhotosForItem(unite: String, famille: String, item: String): Flow<List<Photo>> =
        db.photoDao().getForItem(unite, famille, item)

    /** Photos de la bride uniquement (une bride ne doit jamais voir les photos d'une autre bride du même item). */
    fun getPhotosForBride(unite: String, famille: String, item: String, rep: String): Flow<List<Photo>> =
        db.photoDao().getForBride(unite, famille, item, rep)

    suspend fun addPhoto(photo: Photo): Long {
        val id = db.photoDao().insert(photo)
        touchDate()
        touchItemRevision(photo.unite, photo.famille, photo.item)
        return id
    }

    suspend fun deletePhoto(photo: Photo) {
        db.photoDao().delete(photo)
        touchDate()
        touchItemRevision(photo.unite, photo.famille, photo.item)
    }

    // Révisions (traçabilité après export PDF)
    fun getItemRevision(unite: String, famille: String, item: String): Flow<ItemRevision?> =
        db.itemRevisionDao().get(unite, famille, item)

    suspend fun getItemRevisionOnce(unite: String, famille: String, item: String): ItemRevision? =
        db.itemRevisionDao().getOnce(unite, famille, item)

    /**
     * Marque l'item comme modifié : si un export PDF avait déjà eu lieu et qu'aucune révision
     * n'était encore en cours depuis (revision == exportedRevision), fait avancer la révision
     * d'un cran. Les modifications suivantes, avant le prochain export, ne la font pas ré-avancer.
     */
    private suspend fun touchItemRevision(unite: String, famille: String, item: String) {
        val dao = db.itemRevisionDao()
        val current = dao.getOnce(unite, famille, item) ?: ItemRevision(unite = unite, famille = famille, item = item)
        val bumped = if (current.exportedRevision >= 0 && current.revision == current.exportedRevision) {
            current.copy(revision = current.revision + 1)
        } else current
        dao.upsert(bumped.copy(lastModified = System.currentTimeMillis()))
    }

    /** Instantané des contrôles de chaque bride tel qu'il était au dernier export PDF de l'item (vide si jamais exporté). */
    suspend fun getInspectionBaselineForItem(unite: String, famille: String, item: String): List<InspectionBaseline> =
        db.inspectionBaselineDao().getForItem(unite, famille, item)

    /**
     * Figée après un export PDF réussi : la révision courante devient la nouvelle référence
     * exportée, et l'état actuel des contrôles de chaque bride est capturé comme nouvelle "base"
     * (remplace l'instantané précédent) — c'est à cette base que le prochain export comparera
     * l'état courant pour savoir quelles brides dupliquer en "base" + "révisée".
     */
    suspend fun markItemExported(unite: String, famille: String, item: String) {
        val dao = db.itemRevisionDao()
        val current = dao.getOnce(unite, famille, item) ?: ItemRevision(unite = unite, famille = famille, item = item)
        dao.upsert(current.copy(exportedRevision = current.revision, lastModified = System.currentTimeMillis()))

        val inspections = db.inspectionResultDao().getForItem(unite, famille, item).first()
        db.inspectionBaselineDao().deleteForItem(unite, famille, item)
        db.inspectionBaselineDao().upsertAll(inspections.map { it.toBaseline() })
    }

    suspend fun countPhotos(unite: String, famille: String, item: String): Int =
        db.photoDao().countForItem(unite, famille, item)

    // Schéma / plan de l'équipement
    fun getSchemaForItem(unite: String, famille: String, item: String): Flow<ItemSchema?> =
        db.itemSchemaDao().getForItem(unite, famille, item)

    private fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())

    /** Met à jour uniquement la Date de l'en-tête suite à une modification de données. */
    private suspend fun touchDate() {
        val current = db.pvHeaderDao().getHeader().first() ?: PvHeader()
        db.pvHeaderDao().save(current.copy(date = today()))
    }
}

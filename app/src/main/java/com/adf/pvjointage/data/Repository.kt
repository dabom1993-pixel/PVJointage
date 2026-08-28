package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val current = db.pvHeaderDao().getHeaderOnce() ?: PvHeader()
            db.pvHeaderDao().save(current.copy(client = parsed.client, lieu = parsed.lieu, date = today()))
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
        val brides = db.brideCatalogDao().getAllOnce()
        val inspections = db.inspectionResultDao().getAllOnce()
            .associateBy { "${it.unite}|${it.famille}|${it.item}|${it.rep}" }
        ExcelNativeExporter(appContext).export(referenceExcelFile(), sheetName, brides, inspections)
    }

    private fun referenceExcelFile(): File {
        val dir = File(appContext.getExternalFilesDir(null), "reference")
        dir.mkdirs()
        return File(dir, "classeur_reference.xlsm")
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
        return id
    }

    suspend fun deletePhoto(photo: Photo) {
        db.photoDao().delete(photo)
        touchDate()
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

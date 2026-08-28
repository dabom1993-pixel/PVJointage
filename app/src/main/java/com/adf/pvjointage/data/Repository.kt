package com.adf.pvjointage.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Repository(private val appContext: Context) {
    private val db = AppDatabase.getInstance(appContext)
    private val seedImporter = SeedImporter(appContext.applicationContext, db)

    suspend fun ensureSeedData() = seedImporter.importIfNeeded()

    /**
     * Réimporte le catalogue des brides depuis l'onglet "1-Trame" d'un fichier Excel choisi par
     * l'utilisateur, en écrasant le catalogue existant (brides + items dérivés). Retourne le
     * nombre de brides importées.
     */
    suspend fun importBridesFromExcel(uri: Uri): Int {
        val brides = ExcelImporter(appContext).parseTrame(uri)
        db.withTransaction {
            db.brideCatalogDao().deleteAll()
            db.brideCatalogDao().insertAll(brides)
            db.itemCatalogDao().deleteAll()
            val items = brides
                .map { ItemCatalog(unite = it.unite, famille = it.famille, item = it.item) }
                .distinctBy { Triple(it.unite, it.famille, it.item) }
            db.itemCatalogDao().insertAll(items)
        }
        return brides.size
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

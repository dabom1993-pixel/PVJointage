package com.adf.pvjointage.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class Repository(context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val seedImporter = SeedImporter(context.applicationContext, db)

    suspend fun ensureSeedData() = seedImporter.importIfNeeded()

    // Catalogue
    fun getUnites(): Flow<List<String>> = db.itemCatalogDao().getUnites()
    fun getFamilles(unite: String): Flow<List<String>> = db.itemCatalogDao().getFamilles(unite)
    fun getItems(unite: String, famille: String): Flow<List<String>> = db.itemCatalogDao().getItems(unite, famille)
    fun getBrides(unite: String, famille: String, item: String): Flow<List<BrideCatalog>> =
        db.brideCatalogDao().getBrides(unite, famille, item)

    // En-tête PV
    fun getHeader(): Flow<PvHeader?> = db.pvHeaderDao().getHeader()
    suspend fun saveHeader(header: PvHeader) = db.pvHeaderDao().save(header)

    // Résultats de contrôle
    fun getInspectionsForItem(unite: String, famille: String, item: String): Flow<List<InspectionResult>> =
        db.inspectionResultDao().getForItem(unite, famille, item)

    suspend fun getInspectionForBride(unite: String, famille: String, item: String, rep: String): InspectionResult? =
        db.inspectionResultDao().getForBride(unite, famille, item, rep)

    suspend fun saveInspection(result: InspectionResult) = db.inspectionResultDao().upsert(result)

    // Photos
    fun getPhotosForItem(unite: String, famille: String, item: String): Flow<List<Photo>> =
        db.photoDao().getForItem(unite, famille, item)

    suspend fun addPhoto(photo: Photo): Long = db.photoDao().insert(photo)
    suspend fun deletePhoto(photo: Photo) = db.photoDao().delete(photo)
    suspend fun countPhotos(unite: String, famille: String, item: String): Int =
        db.photoDao().countForItem(unite, famille, item)

    // Schéma / plan de l'équipement
    fun getSchemaForItem(unite: String, famille: String, item: String): Flow<ItemSchema?> =
        db.itemSchemaDao().getForItem(unite, famille, item)

    suspend fun saveSchema(schema: ItemSchema) = db.itemSchemaDao().upsert(schema)
}

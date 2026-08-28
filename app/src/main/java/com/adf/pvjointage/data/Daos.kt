package com.adf.pvjointage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemCatalogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ItemCatalog>)

    @Query("DELETE FROM item_catalog")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM item_catalog")
    suspend fun count(): Int

    @Query("SELECT DISTINCT unite FROM item_catalog ORDER BY unite")
    fun getUnites(): Flow<List<String>>

    @Query("SELECT DISTINCT famille FROM item_catalog WHERE unite = :unite ORDER BY famille")
    fun getFamilles(unite: String): Flow<List<String>>

    @Query("SELECT DISTINCT item FROM item_catalog WHERE unite = :unite AND famille = :famille ORDER BY item")
    fun getItems(unite: String, famille: String): Flow<List<String>>
}

@Dao
interface BrideCatalogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(brides: List<BrideCatalog>)

    @Query("DELETE FROM bride_catalog")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM bride_catalog")
    suspend fun count(): Int

    @Query("SELECT * FROM bride_catalog WHERE unite = :unite AND famille = :famille AND item = :item ORDER BY rep")
    fun getBrides(unite: String, famille: String, item: String): Flow<List<BrideCatalog>>

    /** Tout le catalogue, tous items confondus (export natif Excel). */
    @Query("SELECT * FROM bride_catalog")
    suspend fun getAllOnce(): List<BrideCatalog>
}

@Dao
interface PvHeaderDao {
    @Query("SELECT * FROM pv_header WHERE id = 1")
    fun getHeader(): Flow<PvHeader?>

    @Query("SELECT * FROM pv_header WHERE id = 1")
    suspend fun getHeaderOnce(): PvHeader?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(header: PvHeader)
}

@Dao
interface InspectionResultDao {
    @Query("SELECT * FROM inspection_result WHERE unite = :unite AND famille = :famille AND item = :item")
    fun getForItem(unite: String, famille: String, item: String): Flow<List<InspectionResult>>

    @Query("SELECT * FROM inspection_result WHERE unite = :unite AND famille = :famille AND item = :item AND rep = :rep LIMIT 1")
    suspend fun getForBride(unite: String, famille: String, item: String, rep: String): InspectionResult?

    /** Tous les contrôles, tous items confondus (export natif Excel). */
    @Query("SELECT * FROM inspection_result")
    suspend fun getAllOnce(): List<InspectionResult>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: InspectionResult)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photo WHERE unite = :unite AND famille = :famille AND item = :item ORDER BY dateAjout DESC")
    fun getForItem(unite: String, famille: String, item: String): Flow<List<Photo>>

    @Query("SELECT * FROM photo WHERE unite = :unite AND famille = :famille AND item = :item AND rep = :rep ORDER BY dateAjout DESC")
    fun getForBride(unite: String, famille: String, item: String, rep: String): Flow<List<Photo>>

    @Insert
    suspend fun insert(photo: Photo): Long

    @Delete
    suspend fun delete(photo: Photo)

    @Query("SELECT COUNT(*) FROM photo WHERE unite = :unite AND famille = :famille AND item = :item")
    suspend fun countForItem(unite: String, famille: String, item: String): Int
}

@Dao
interface ItemSchemaDao {
    @Query("SELECT * FROM item_schema WHERE unite = :unite AND famille = :famille AND item = :item LIMIT 1")
    fun getForItem(unite: String, famille: String, item: String): Flow<ItemSchema?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schema: ItemSchema)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schemas: List<ItemSchema>)

    @Query("DELETE FROM item_schema")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM item_schema")
    suspend fun count(): Int
}

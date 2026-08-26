package com.adf.pvjointage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ItemCatalog::class, BrideCatalog::class, PvHeader::class, InspectionResult::class, Photo::class, ItemSchema::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemCatalogDao(): ItemCatalogDao
    abstract fun brideCatalogDao(): BrideCatalogDao
    abstract fun pvHeaderDao(): PvHeaderDao
    abstract fun inspectionResultDao(): InspectionResultDao
    abstract fun photoDao(): PhotoDao
    abstract fun itemSchemaDao(): ItemSchemaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pv_jointage.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.adf.pvjointage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ItemCatalog::class, BrideCatalog::class, PvHeader::class, InspectionResult::class, Photo::class, ItemSchema::class],
    version = 2,
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

        /** Ajout du champ libre "Remarque" (5 lignes max) sur chaque contrôle de bride. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inspection_result ADD COLUMN remarque TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pv_jointage.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

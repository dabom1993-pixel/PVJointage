package com.adf.pvjointage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ItemCatalog::class, BrideCatalog::class, PvHeader::class, InspectionResult::class, Photo::class, ItemSchema::class, ItemRevision::class, InspectionBaseline::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemCatalogDao(): ItemCatalogDao
    abstract fun brideCatalogDao(): BrideCatalogDao
    abstract fun pvHeaderDao(): PvHeaderDao
    abstract fun inspectionResultDao(): InspectionResultDao
    abstract fun photoDao(): PhotoDao
    abstract fun itemSchemaDao(): ItemSchemaDao
    abstract fun itemRevisionDao(): ItemRevisionDao
    abstract fun inspectionBaselineDao(): InspectionBaselineDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Ajout du champ libre "Remarque" (5 lignes max) sur chaque contrôle de bride. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inspection_result ADD COLUMN remarque TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Ajout des colonnes de référence "LgB" / "DiamB" (longueur/diamètre de boulon). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bride_catalog ADD COLUMN longueurBoulon TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bride_catalog ADD COLUMN diametreBoulon TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Ajout de la colonne de référence "NeufB" (boulonnerie neuve de référence). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bride_catalog ADD COLUMN neufBoulon TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Ajout de la table de traçabilité des révisions (gestion des révisions après export PDF). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_revision (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "unite TEXT NOT NULL, famille TEXT NOT NULL, item TEXT NOT NULL, " +
                        "revision INTEGER NOT NULL DEFAULT 0, " +
                        "exportedRevision INTEGER NOT NULL DEFAULT -1, " +
                        "lastModified INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_item_revision_unite_famille_item " +
                        "ON item_revision (unite, famille, item)"
                )
            }
        }

        /** Ajout de la table d'instantané des contrôles au dernier export (dédoublement base/révisée du PDF). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS inspection_baseline (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "unite TEXT NOT NULL, famille TEXT NOT NULL, item TEXT NOT NULL, rep TEXT NOT NULL, " +
                        "etiMiseSerree TEXT NOT NULL DEFAULT '', etiNomDateLisible TEXT NOT NULL DEFAULT '', " +
                        "jointMatiereConforme TEXT NOT NULL DEFAULT '', jointDimensionCentrage TEXT NOT NULL DEFAULT '', jointAspectNeuf TEXT NOT NULL DEFAULT '', " +
                        "boulonNeuves TEXT NOT NULL DEFAULT '', boulonRondelles TEXT NOT NULL DEFAULT '', boulonEquilibrage TEXT NOT NULL DEFAULT '', " +
                        "boulonGraissage TEXT NOT NULL DEFAULT '', boulonLongueurDiametre TEXT NOT NULL DEFAULT '', boulonMatiere TEXT NOT NULL DEFAULT '', " +
                        "assemblageParallelisme TEXT NOT NULL DEFAULT '', assemblageExcentration TEXT NOT NULL DEFAULT '', " +
                        "remarque TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_inspection_baseline_unite_famille_item_rep " +
                        "ON inspection_baseline (unite, famille, item, rep)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pv_jointage.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

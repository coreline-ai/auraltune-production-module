// AutoEqDatabase.kt
// Phase 4: Room database for the DB-first AutoEQ catalog. Profile filter storage (Phase 5)
// will add tables/version bumps here with proper migrations; schemas are exported under
// autoeq-data/schemas for migration tests.
package com.coreline.autoeq.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CatalogEntryEntity::class,
        SyncStateEntity::class,
        ProfileEntity::class,
        ProfileFilterEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AutoEqDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun profileDao(): ProfileDao

    companion object {
        private const val DB_NAME = "autoeq_catalog.db"

        @Volatile
        private var instance: AutoEqDatabase? = null

        /**
         * v1→v2 (Phase 5): additive — adds `profiles` + `profile_filters`. Catalog tables
         * untouched. SQL mirrors the Room-generated schema (schemas/...AutoEqDatabase/2.json).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (`id` TEXT NOT NULL, `catalogId` TEXT, " +
                        "`name` TEXT NOT NULL, `measuredBy` TEXT, `source` TEXT NOT NULL, " +
                        "`preampDb` REAL NOT NULL, `optimizedSampleRate` REAL NOT NULL, " +
                        "`sourceUrl` TEXT, `sourceSha256` TEXT, `fetchedAtMs` INTEGER NOT NULL, " +
                        "`lastAccessMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profile_filters` (`profileId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `type` TEXT NOT NULL, `frequencyHz` REAL NOT NULL, " +
                        "`gainDb` REAL NOT NULL, `q` REAL NOT NULL, PRIMARY KEY(`profileId`, `position`), " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_profile_filters_profileId` " +
                        "ON `profile_filters` (`profileId`)",
                )
            }
        }

        /** Bundled prebuilt seed: full catalog + all parsed profiles (tools/gen_autoeq_seed.py). */
        private const val SEED_ASSET = "databases/autoeq_seed.db"

        /**
         * Process-wide singleton. Fresh installs are populated from the prebuilt [SEED_ASSET]
         * (createFromAsset) so the entire catalog AND every profile are available offline with
         * zero first-run network. Room handles its own threading; DAO calls are `suspend`.
         *
         * Note: createFromAsset only applies when no DB file exists yet. Existing installs keep
         * their DB and fill profiles on-demand; delta sync keeps both current.
         */
        fun get(context: Context): AutoEqDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoEqDatabase::class.java,
                    DB_NAME,
                ).createFromAsset(SEED_ASSET)
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}

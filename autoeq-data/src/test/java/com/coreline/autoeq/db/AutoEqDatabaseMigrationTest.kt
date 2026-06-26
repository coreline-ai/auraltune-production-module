package com.coreline.autoeq.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutoEqDatabaseMigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dbName = "autoeq-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 1 to 2 preserves catalog and creates profile tables`() {
        createVersion1Database()

        val migrated = Room.databaseBuilder(context, AutoEqDatabase::class.java, dbName)
            .addMigrations(AutoEqDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val catalogRows = migrated.openHelper.writableDatabase
                .query("SELECT id, name FROM catalog_entries WHERE id = 'hd600'")
            catalogRows.use {
                assertEquals(1, it.count)
                assertEquals(true, it.moveToFirst())
                assertEquals("Sennheiser HD 600", it.getString(1))
            }

            val profileTable = migrated.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='profiles'")
            profileTable.use {
                assertEquals(1, it.count)
                assertEquals(true, it.moveToFirst())
            }

            val filterIndex = migrated.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_profile_filters_profileId'")
            filterIndex.use {
                assertEquals(1, it.count)
                assertEquals(true, it.moveToFirst())
            }

            assertNotNull(migrated.profileDao())
        } finally {
            migrated.close()
        }
    }

    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `catalog_entries` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `normalizedName` TEXT NOT NULL,
                    `measuredBy` TEXT NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `lastSeenAtMs` INTEGER NOT NULL,
                    `isDeleted` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_entries_normalizedName` ON `catalog_entries` (`normalizedName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_entries_isDeleted` ON `catalog_entries` (`isDeleted`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_state` (
                    `key` TEXT NOT NULL,
                    `etag` TEXT,
                    `contentSha256` TEXT,
                    `seedVersion` INTEGER NOT NULL,
                    `lastSyncAtMs` INTEGER NOT NULL,
                    `status` TEXT,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `catalog_entries`
                    (`id`, `name`, `normalizedName`, `measuredBy`, `relativePath`, `lastSeenAtMs`, `isDeleted`)
                VALUES
                    ('hd600', 'Sennheiser HD 600', 'sennheiser hd 600', 'oratory1990', 'oratory1990/over-ear/Sennheiser HD 600', 1, 0)
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '8fefc411a9fb8a9c7bb373e189f657b3')")
            db.version = 1
        }
    }
}

// OpraDatabase.kt
// Phase 3 — Room database for OPRA. Separate DB file from :autoeq-data. Unlike AutoEq (which
// ships a prebuilt seed via createFromAsset), OPRA is populated by IMPORTING database_v1.jsonl
// (bundled snapshot and/or mirror) through OpraJsonlParser, so there is no createFromAsset here.
// Schemas exported under opra-data/schemas for future migration tests.
package com.coreline.auraltune.opra.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        OpraCatalogEntryEntity::class,
        OpraEqProfileEntity::class,
        OpraEqFilterEntity::class,
        OpraSyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpraDatabase : RoomDatabase() {
    abstract fun opraDao(): OpraDao

    companion object {
        private const val DB_NAME = "opra_catalog.db"

        @Volatile
        private var instance: OpraDatabase? = null

        fun get(context: Context): OpraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OpraDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}

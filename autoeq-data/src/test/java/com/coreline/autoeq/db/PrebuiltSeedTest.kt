package com.coreline.autoeq.db

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.coreline.audio.EqFilterType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * QA verification of the prebuilt Room seed loaded via createFromAsset
 * (autoeq-data/src/main/assets/databases/autoeq_seed.db).
 *
 * The make-or-break assertion: the database OPENS without an IllegalStateException.
 * Room validates the on-disk schema's identityHash against the compiled @Database
 * identityHash (5674e7e3ff... from schemas/.../2.json) the first time it is opened.
 * A mismatch throws `IllegalStateException: Pre-packaged database has an invalid
 * schema`. So a clean open + working DAO queries PROVE the seed matches schema v2.
 *
 * Runs under Robolectric with `isIncludeAndroidResources = true` (see build.gradle.kts),
 * which exposes the module's main/ assets to the test classloader so createFromAsset
 * can copy the prebuilt file out of the asset stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrebuiltSeedTest {

    private lateinit var db: AutoEqDatabase
    private lateinit var dbFile: File

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (this::dbFile.isInitialized) {
            dbFile.delete()
            File(dbFile.parentFile, dbFile.name + "-shm").delete()
            File(dbFile.parentFile, dbFile.name + "-wal").delete()
        }
    }

    private fun openSeed(): AutoEqDatabase {
        val app = RuntimeEnvironment.getApplication()
        // Use a unique target name so each test run unpacks a fresh copy from the asset.
        dbFile = File(app.getDatabasePath("verify_seed.db").absolutePath)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        return Room.databaseBuilder(app, AutoEqDatabase::class.java, dbFile.name)
            .createFromAsset("databases/autoeq_seed.db")
            .addMigrations(AutoEqDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `createFromAsset opens seed - identityHash matches schema v2`() = runBlocking {
        // The build() above is lazy; the first DAO call forces the open + the Room
        // identity-hash validation against room_master_table. If the prebuilt DB's
        // schema did NOT match @Database(version=2) this line throws
        // IllegalStateException. Reaching the assertions PROVES the hash matches.
        db = openSeed()

        val catalogCount = db.catalogDao().count()
        val profileCount = db.profileDao().count()

        assertTrue("catalog count must be > 6000, was $catalogCount", catalogCount > 6000)
        assertTrue("profile count must be > 6000, was $profileCount", profileCount > 6000)
    }

    @Test
    fun `seeded profile has well-formed filters`() = runBlocking {
        db = openSeed()

        // Pull one profile id directly from the seeded profiles table via a raw query.
        val cursor = db.query(SimpleSQLiteQuery("SELECT id FROM profiles LIMIT 1"))
        val profileId: String = cursor.use {
            assertTrue("expected at least one profile row", it.moveToFirst())
            it.getString(0)
        }

        val now = System.currentTimeMillis()
        val profile = ProfileStore(db.profileDao()).read(profileId, now)
        assertNotNull("seeded profile $profileId should be readable", profile)
        profile!!

        // AutoEq chains are at most 10 sections; expect a populated, plausible set.
        assertTrue(
            "profile should have 1..10 filters, had ${profile.filters.size}",
            profile.filters.isNotEmpty() && profile.filters.size <= AutoEqProfileLimit,
        )
        val allowed = setOf(
            EqFilterType.PEAKING,
            EqFilterType.LOW_SHELF,
            EqFilterType.HIGH_SHELF,
            EqFilterType.HIGH_PASS,
        )
        profile.filters.forEachIndexed { i, f ->
            assertTrue("filter $i freq in (0,24000], was ${f.frequency}",
                f.frequency > 0.0 && f.frequency <= 24000.0)
            assertTrue("filter $i |gain| <= 30, was ${f.gainDB}",
                kotlin.math.abs(f.gainDB) <= 30f)
            assertTrue("filter $i q > 0, was ${f.q}", f.q > 0.0)
            assertTrue("filter $i type ${f.type} must be in $allowed", f.type in allowed)
        }
    }

    companion object {
        private const val AutoEqProfileLimit = 10
    }
}

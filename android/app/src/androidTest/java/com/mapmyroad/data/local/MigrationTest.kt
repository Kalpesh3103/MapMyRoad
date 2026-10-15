package com.mapmyroad.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migrations (CODING_STANDARDS.md section 3.10). Reads the exported schemas from `app/schemas` (androidTest
 * assets). Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MapMyRoadDatabase::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate1To2_keepsEveryTable_andAddsUploadQueue() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertVersion1Rows(db)
        }

        // Validates the migrated schema against 2.json, including dropped tables.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseMigrations.MIGRATION_1_2)

        assertEquals(1, db.count("trip"))
        assertEquals(2, db.count("trip_point"))
        assertEquals(1, db.count("hazard_event_local"))
        assertEquals(1, db.count("segment_sample_local"))
        assertEquals(0, db.count("upload_queue"))

        db.execSQL(
            "INSERT INTO upload_queue (trip_id, state, attempts, next_attempt_at, last_error) " +
                "VALUES ('trip-1', 'pending', 0, 0, NULL)",
        )
        assertEquals(1, db.count("upload_queue"))
        // At most one row per trip.
        try {
            db.execSQL(
                "INSERT INTO upload_queue (trip_id, state, attempts, next_attempt_at, last_error) " +
                    "VALUES ('trip-1', 'pending', 0, 0, NULL)",
            )
            fail("A second queue row for the same trip was accepted")
        } catch (expected: SQLiteConstraintException) {
            // Expected: unique index on trip_id.
        }
        // Deleting the trip removes its queue row.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM trip WHERE id = 'trip-1'")
        assertEquals(0, db.count("upload_queue"))
        db.close()
    }

    @Test
    fun migratedDatabase_opensWithRoom_andQueuesFinishedTrips() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertVersion1Rows(db)
        }

        val database =
            Room
                .databaseBuilder(context, MapMyRoadDatabase::class.java, TEST_DB)
                .addMigrations(*DatabaseMigrations.ALL)
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                val uploadDao = database.uploadDao()
                // trip-1 started at 1000: an opt-in after that never queues it.
                uploadDao.refreshQueue(nowMs = 5_000L, enabledAtMs = 1_001L)
                assertEquals(0, uploadDao.countWaiting())

                uploadDao.refreshQueue(nowMs = 5_000L, enabledAtMs = 1_000L)
                uploadDao.refreshQueue(nowMs = 6_000L, enabledAtMs = 1_000L)

                val due = uploadDao.getDue(nowMs = 5_000L, maxRows = 10)
                assertEquals(listOf("trip-1"), due.map { it.tripId })
                assertEquals(UploadQueueState.PENDING, due.single().state)
                assertEquals(1, uploadDao.getHazardsNotUploaded("trip-1").size)

                uploadDao.markUploaded(hazardIds = listOf("hazard-1"), sampleIds = listOf("sample-1"))
                assertTrue(uploadDao.getHazardsNotUploaded("trip-1").isEmpty())
                assertTrue(uploadDao.getSamplesNotUploaded("trip-1").isEmpty())
                assertEquals(2, uploadDao.countUploaded("trip-1"))

                // Re-enabling later moves the cutoff: the still-pending row of the older trip is skipped.
                uploadDao.refreshQueue(nowMs = 7_000L, enabledAtMs = 9_000L)
                val row = uploadDao.getForTrip("trip-1")
                assertEquals(UploadQueueState.SKIPPED, row?.state)
                assertEquals(UploadQueueState.CATEGORY_BEFORE_OPT_IN, row?.lastError)

                database.tripDao().deleteAll()
                assertEquals(0, uploadDao.countWaiting())
            }
        } finally {
            database.close()
        }
    }

    private fun insertVersion1Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO trip (id, trip_token, vehicle_type, started_at, ended_at, distance_m, duration_s, " +
                "smoothness_score, minor_count, moderate_count, severe_count, start_mode, stop_reason) " +
                "VALUES ('trip-1', 'token-1', 2, 1000, 2000, 1500.0, 60, 80, 1, 0, 0, 'manual', 'manual')",
        )
        db.execSQL(
            "INSERT INTO trip_point (trip_id, t, lat, lon, speed_mps, accuracy_m, roughness) VALUES " +
                "('trip-1', 1000, 18.5, 73.8, 8.0, 5.0, NULL), ('trip-1', 2000, 18.6, 73.9, 8.0, 5.0, 0.3)",
        )
        db.execSQL(
            "INSERT INTO hazard_event_local (id, trip_id, lat, lon, observed_at, severity, speed_kmh, peak_score, " +
                "heading_deg, uploaded) VALUES ('hazard-1', 'trip-1', 18.55, 73.85, 1500, 2, 30.0, 2.5, 90, 0)",
        )
        db.execSQL(
            "INSERT INTO segment_sample_local (id, trip_id, start_lat, start_lon, end_lat, end_lon, observed_at, " +
                "length_m, roughness, speed_kmh, uploaded) " +
                "VALUES ('sample-1', 'trip-1', 18.55, 73.85, 18.56, 73.86, 1500, 50.0, 0.4, 30.0, 0)",
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

package com.mapmyroad.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Migration tests: there is only schema version 1, so there is nothing to migrate yet. When version 2 adds
// `upload_queue`, add a MigrationTestHelper test here (needs androidx.room:room-testing in androidTest and the
// exported schemas in app/schemas as androidTest assets), covering 1 -> 2 with data in every table.

@RunWith(AndroidJUnit4::class)
class TripDaoTest {
    private lateinit var database: MapMyRoadDatabase
    private lateinit var dao: TripDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, MapMyRoadDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.tripDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deleteTrip_cascadesToPointsHazardsAndSamples() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = 2_000L))
            dao.insertTrip(trip("b", endedAt = 3_000L))
            dao.appendRecording(
                points = listOf(point("a", 1L), point("a", 2L)),
                hazards = listOf(hazard("ha", "a")),
                samples = listOf(sample("sa", "a")),
            )
            dao.appendRecording(listOf(point("b", 1L)), listOf(hazard("hb", "b")), listOf(sample("sb", "b")))

            assertEquals(1, dao.deleteTrip("a"))

            assertNull(dao.getTrip("a"))
            assertTrue(dao.getPoints("a").isEmpty())
            assertTrue(dao.getHazards("a").isEmpty())
            assertTrue(dao.getSamples("a").isEmpty())
            assertEquals(1, dao.getPoints("b").size)
            assertEquals(1, dao.getHazards("b").size)
            assertEquals(1, dao.getSamples("b").size)
        }

    @Test
    fun retriedBatch_doesNotDuplicateHazardsOrSamples() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = null))
            val hazards = listOf(hazard("h1", "a"), hazard("h2", "a"))
            val samples = listOf(sample("s1", "a"))

            dao.appendRecording(emptyList(), hazards, samples)
            dao.appendRecording(emptyList(), hazards, samples)

            assertEquals(2, dao.getHazards("a").size)
            assertEquals(1, dao.getSamples("a").size)
        }

    @Test
    fun insertTrip_isIgnored_whenIdExists() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = null))

            val rowId = dao.insertTrip(trip("a", endedAt = 5_000L))

            assertEquals(-1L, rowId)
            assertNull(dao.getTrip("a")?.endedAt)
        }

    @Test
    fun appendRecording_forMissingTrip_writesNothing() =
        runBlocking {
            val result =
                runCatching {
                    dao.appendRecording(listOf(point("missing", 1L)), listOf(hazard("h", "missing")), emptyList())
                }

            assertTrue(result.isFailure)
            assertTrue(dao.getPoints("missing").isEmpty())
            assertTrue(dao.getHazards("missing").isEmpty())
        }

    @Test
    fun finishedAndUnfinishedQueries_areSeparated() =
        runBlocking {
            dao.insertTrip(trip("old", startedAt = 1_000L, endedAt = 2_000L))
            dao.insertTrip(trip("new", startedAt = 5_000L, endedAt = 6_000L))
            dao.insertTrip(trip("live", startedAt = 9_000L, endedAt = null))
            dao.appendRecording(
                points = listOf(point("new", 5_500L), point("live", 9_500L)),
                hazards = listOf(hazard("hn", "new"), hazard("hl", "live")),
                samples = emptyList(),
            )

            assertEquals(listOf("new", "old"), dao.observeFinishedTrips().first().map { it.id })
            assertEquals("new", dao.observeLatestFinishedTrip().first()?.id)
            assertEquals(listOf("live"), dao.getUnfinishedTrips().map { it.id })
            assertEquals(listOf("new"), dao.observeFinishedTripPoints().first().map { it.tripId })
            assertEquals(listOf("hn"), dao.observeFinishedTripHazards().first().map { it.id })
        }

    @Test
    fun updateSummary_finishesTrip() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = null))

            val updated =
                dao.updateSummary(
                    tripId = "a",
                    endedAt = 60_000L,
                    distanceM = 1_500.0,
                    durationS = 59,
                    smoothnessScore = 77,
                    minorCount = 1,
                    moderateCount = 2,
                    severeCount = 3,
                    stopReason = "manual",
                )

            assertEquals(1, updated)
            val trip = dao.observeTrip("a").first()
            assertNotNull(trip)
            assertEquals(60_000L, trip?.endedAt)
            assertEquals(77, trip?.smoothnessScore)
            assertEquals("manual", trip?.stopReason)
            assertEquals(listOf("a"), dao.observeFinishedTrips().first().map { it.id })
        }

    @Test
    fun points_areReturnedInTimeOrder() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = null))
            dao.appendRecording(listOf(point("a", 3L), point("a", 1L), point("a", 2L)), emptyList(), emptyList())

            assertEquals(listOf(1L, 2L, 3L), dao.getPoints("a").map { it.t })
            assertEquals(listOf(1L, 2L, 3L), dao.observePoints("a").first().map { it.t })
        }

    @Test
    fun deleteAll_clearsEveryTable() =
        runBlocking {
            dao.insertTrip(trip("a", endedAt = 2_000L))
            dao.insertTrip(trip("b", endedAt = null))
            dao.appendRecording(listOf(point("a", 1L)), listOf(hazard("ha", "a")), listOf(sample("sa", "a")))
            dao.appendRecording(listOf(point("b", 1L)), listOf(hazard("hb", "b")), listOf(sample("sb", "b")))

            dao.deleteAll()

            assertTrue(dao.observeFinishedTrips().first().isEmpty())
            assertTrue(dao.getUnfinishedTrips().isEmpty())
            listOf("a", "b").forEach { id ->
                assertTrue(dao.getPoints(id).isEmpty())
                assertTrue(dao.getHazards(id).isEmpty())
                assertTrue(dao.getSamples(id).isEmpty())
            }
        }

    // Test data: fixed, synthetic coordinates only.

    private fun trip(
        id: String,
        startedAt: Long = 1_000L,
        endedAt: Long?,
    ) = TripEntity(
        id = id,
        tripToken = "token-$id",
        vehicleType = 1,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceM = 0.0,
        durationS = 0,
        smoothnessScore = null,
        minorCount = 0,
        moderateCount = 0,
        severeCount = 0,
        startMode = "manual",
        stopReason = null,
    )

    private fun point(
        tripId: String,
        t: Long,
    ) = TripPointEntity(tripId = tripId, t = t, lat = 18.5, lon = 73.85, speedMps = 10.0, accuracyM = 5.0, roughness = null)

    private fun hazard(
        id: String,
        tripId: String,
    ) = HazardEventLocalEntity(
        id = id,
        tripId = tripId,
        lat = 18.5,
        lon = 73.85,
        observedAt = 1_500L,
        severity = 2,
        speedKmh = 30.0,
        peakScore = 2.0,
        headingDeg = 90,
    )

    private fun sample(
        id: String,
        tripId: String,
    ) = SegmentSampleLocalEntity(
        id = id,
        tripId = tripId,
        startLat = 18.5,
        startLon = 73.85,
        endLat = 18.5009,
        endLon = 73.85,
        observedAt = 1_500L,
        lengthM = 100.0,
        roughness = 1.0,
        speedKmh = 36.0,
    )
}

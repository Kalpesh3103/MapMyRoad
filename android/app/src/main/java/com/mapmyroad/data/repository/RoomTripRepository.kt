package com.mapmyroad.data.repository

import androidx.room.withTransaction
import com.mapmyroad.data.local.HazardEventLocalEntity
import com.mapmyroad.data.local.MapMyRoadDatabase
import com.mapmyroad.data.local.TripDao
import com.mapmyroad.data.local.TripEntity
import com.mapmyroad.data.local.TripPointEntity
import com.mapmyroad.data.local.toDomain
import com.mapmyroad.data.local.toDomainOrNull
import com.mapmyroad.data.local.toEntity
import com.mapmyroad.di.IoDispatcher
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.model.TripTrack
import com.mapmyroad.domain.repository.TripRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TripRepository] backed by Room (DATABASE_SCHEMA.md Part B). Room runs queries on its own executor; mapping and
 * summary math run on [ioDispatcher]. Logs contain trip ids and counts only, never coordinates
 * (CODING_STANDARDS.md section 3.7).
 */
@Singleton
class RoomTripRepository
    @Inject
    constructor(
        private val database: MapMyRoadDatabase,
        private val tripDao: TripDao,
        private val summaryCalculator: TripSummaryCalculator,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TripRepository {
        override fun observeFinishedTrips(): Flow<List<Trip>> =
            tripDao
                .observeFinishedTrips()
                .map(::toTrips)
                .flowOn(ioDispatcher)

        override fun observeTrip(tripId: String): Flow<Trip?> =
            tripDao
                .observeTrip(tripId)
                .map { entity -> entity?.let(::toTripOrNull) }
                .flowOn(ioDispatcher)

        override fun observeLatestFinishedTrip(): Flow<Trip?> =
            tripDao
                .observeLatestFinishedTrip()
                .map { entity -> entity?.let(::toTripOrNull) }
                .flowOn(ioDispatcher)

        override fun observeTripPoints(tripId: String): Flow<List<TripPoint>> =
            tripDao
                .observePoints(tripId)
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(ioDispatcher)

        override fun observeTripHazards(tripId: String): Flow<List<RecordedHazard>> =
            tripDao
                .observeHazards(tripId)
                .map(::toHazards)
                .flowOn(ioDispatcher)

        // Memory: this loads every point of every finished trip (about one row per second of driving) whenever
        // `trip` or `trip_point` changes. Fine for the v1 history sizes; each track is then decimated to
        // TrackDecimator.MAX_POINTS_PER_TRIP so the map never gets huge polylines. If histories grow large, store
        // a pre-simplified track per trip at finish time instead.
        override fun observeAllTracks(): Flow<List<TripTrack>> =
            combine(
                tripDao.observeFinishedTrips(),
                tripDao.observeFinishedTripPoints(),
            ) { trips, points -> buildTracks(trips, points) }
                .flowOn(ioDispatcher)

        override fun observeAllHazards(): Flow<List<RecordedHazard>> =
            tripDao
                .observeFinishedTripHazards()
                .map(::toHazards)
                .flowOn(ioDispatcher)

        override suspend fun startTrip(trip: Trip) {
            val rowId =
                withContext(ioDispatcher) {
                    tripDao.insertTrip(trip.toEntity(tripToken = UUID.randomUUID().toString()))
                }
            if (rowId == -1L) {
                Timber.w("startTrip: trip %s already exists, kept the existing row", trip.id)
            }
        }

        /**
         * Rows for a trip id that does not exist violate the foreign key and make the call throw
         * `SQLiteConstraintException` with nothing written. Hazards and samples already stored are ignored (UUID
         * keys), so retrying a batch is safe.
         */
        override suspend fun appendRecording(
            tripId: String,
            points: List<TripPoint>,
            hazards: List<RecordedHazard>,
            samples: List<SegmentSample>,
        ) {
            if (points.isEmpty() && hazards.isEmpty() && samples.isEmpty()) return
            withContext(ioDispatcher) {
                // The tripId argument is authoritative: items are written under it whatever their own tripId says.
                tripDao.appendRecording(
                    points = points.map { it.toEntity().copy(tripId = tripId) },
                    hazards = hazards.map { it.toEntity().copy(tripId = tripId) },
                    samples = samples.map { it.toEntity().copy(tripId = tripId) },
                )
            }
        }

        override suspend fun finishTrip(
            tripId: String,
            endedAtMs: Long,
            stopReason: StopReason,
        ): Trip? =
            withContext(ioDispatcher) {
                val updated =
                    database.withTransaction {
                        val trip = tripDao.getTrip(tripId) ?: return@withTransaction null
                        val summary =
                            summaryCalculator.calculate(
                                startedAtMs = trip.startedAt,
                                endedAtMs = endedAtMs,
                                points = tripDao.getPoints(tripId).map { it.toDomain() },
                                hazards = toHazards(tripDao.getHazards(tripId)),
                                samples = tripDao.getSamples(tripId).map { it.toDomain() },
                            )
                        tripDao.updateSummary(
                            tripId = tripId,
                            endedAt = endedAtMs,
                            distanceM = summary.distanceM,
                            durationS = summary.durationS,
                            smoothnessScore = summary.smoothnessScore,
                            minorCount = summary.minorCount,
                            moderateCount = summary.moderateCount,
                            severeCount = summary.severeCount,
                            stopReason = stopReason.code,
                        )
                        tripDao.getTrip(tripId)
                    }
                if (updated == null) {
                    Timber.w("finishTrip: trip %s not found", tripId)
                    null
                } else {
                    Timber.i("finishTrip: trip %s finished (%s)", tripId, stopReason.code)
                    toTripOrNull(updated)
                }
            }

        override suspend fun findUnfinishedTrips(): List<Trip> =
            withContext(ioDispatcher) {
                toTrips(tripDao.getUnfinishedTrips())
            }

        override suspend fun deleteTrip(tripId: String) {
            val deleted = withContext(ioDispatcher) { tripDao.deleteTrip(tripId) }
            Timber.i("deleteTrip: trip %s, %d row(s) deleted", tripId, deleted)
        }

        override suspend fun deleteAllTrips() {
            withContext(ioDispatcher) { tripDao.deleteAll() }
            Timber.i("deleteAllTrips: done")
        }

        private fun buildTracks(
            trips: List<TripEntity>,
            points: List<TripPointEntity>,
        ): List<TripTrack> {
            val pointsByTrip = points.groupBy { it.tripId }
            return trips.map { trip ->
                val tripPoints = pointsByTrip[trip.id].orEmpty()
                TripTrack(
                    tripId = trip.id,
                    smoothnessScore = trip.smoothnessScore,
                    points = TrackDecimator.decimate(tripPoints).map { it.toDomain() },
                )
            }
        }

        private fun toTrips(entities: List<TripEntity>): List<Trip> = entities.mapNotNull(::toTripOrNull)

        private fun toTripOrNull(entity: TripEntity): Trip? {
            val trip = entity.toDomainOrNull()
            if (trip == null) {
                Timber.w("Skipping trip %s: unknown vehicle_type %d", entity.id, entity.vehicleType)
            }
            return trip
        }

        private fun toHazards(entities: List<HazardEventLocalEntity>): List<RecordedHazard> =
            entities.mapNotNull { entity ->
                val hazard = entity.toDomainOrNull()
                if (hazard == null) {
                    Timber.w("Skipping hazard %s: unknown severity %d", entity.id, entity.severity)
                }
                hazard
            }
    }

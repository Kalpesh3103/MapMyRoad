package com.mapmyroad.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.Severity
import com.mapmyroad.di.DefaultDispatcher
import com.mapmyroad.domain.model.TripTrack
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.RoadQualityGeoJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.maplibre.geojson.FeatureCollection
import javax.inject.Inject

/** State of the single-trip route map on the trip summary (S4). */
@Immutable
data class TripRouteMapUiState(
    val isLoading: Boolean = true,
    val trackFeatures: FeatureCollection = RoadQualityGeoJson.emptyCollection(),
    val hazardFeatures: FeatureCollection = RoadQualityGeoJson.emptyCollection(),
    /** Bounds of the route and its hazards, null when the trip has no points. */
    val bounds: GeoBounds? = null,
    val minorCount: Int = 0,
    val moderateCount: Int = 0,
    val severeCount: Int = 0,
)

/**
 * Loads one trip's points and hazards for [TripRouteMap]. The trip id is set from the composable with
 * [setTripId], so this works in any destination, with or without a `tripId` navigation argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripRouteMapViewModel
    @Inject
    constructor(
        tripRepository: TripRepository,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val tripId = MutableStateFlow<String?>(null)

        val uiState: StateFlow<TripRouteMapUiState> =
            tripId
                .filterNotNull()
                .flatMapLatest { id ->
                    combine(tripRepository.observeTripPoints(id), tripRepository.observeTripHazards(id)) { points, hazards ->
                        val track = TripTrack(tripId = id, smoothnessScore = null, points = points)
                        TripRouteMapUiState(
                            isLoading = false,
                            trackFeatures = RoadQualityGeoJson.trackFeatures(listOf(track)),
                            hazardFeatures = RoadQualityGeoJson.hazardFeatures(hazards),
                            bounds =
                                GeoBounds.union(
                                    listOf(
                                        GeoBounds.of(points, { it.lat }, { it.lon }),
                                        GeoBounds.of(hazards, { it.lat }, { it.lon }),
                                    ),
                                ),
                            minorCount = hazards.count { it.severity == Severity.MINOR },
                            moderateCount = hazards.count { it.severity == Severity.MODERATE },
                            severeCount = hazards.count { it.severity == Severity.SEVERE },
                        )
                    }
                }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripRouteMapUiState())

        fun setTripId(id: String) {
            tripId.value = id
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

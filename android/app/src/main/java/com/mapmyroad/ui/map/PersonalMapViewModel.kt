package com.mapmyroad.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.Severity
import com.mapmyroad.di.DefaultDispatcher
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.TripTrack
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.RoadQualityGeoJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.maplibre.geojson.FeatureCollection
import javax.inject.Inject

/** Counts for the TalkBack summary of the visible map area (UI_UX_GUIDELINES.md section 11). */
@Immutable
data class MapViewSummary(
    val tripCount: Int = 0,
    val minorCount: Int = 0,
    val moderateCount: Int = 0,
    val severeCount: Int = 0,
)

/** State of the personal map (S6). GeoJSON is prebuilt off the main thread. */
@Immutable
data class PersonalMapUiState(
    val isLoading: Boolean = true,
    val tripCount: Int = 0,
    val trackFeatures: FeatureCollection = RoadQualityGeoJson.emptyCollection(),
    val hazardFeatures: FeatureCollection = RoadQualityGeoJson.emptyCollection(),
    /** Bounds of all trips and hazards, null when there is nothing to show. */
    val dataBounds: GeoBounds? = null,
    /** Totals in the visible area, or over all data before the first camera update. */
    val summary: MapViewSummary = MapViewSummary(),
    /** Hazard whose detail sheet (S7) is open. */
    val selectedHazard: RecordedHazard? = null,
) {
    val isEmpty: Boolean get() = !isLoading && tripCount == 0
}

/** Personal map (PRD PM-1, PM-2): the user's own trips colored by smoothness and their hazards. */
@HiltViewModel
class PersonalMapViewModel
    @Inject
    constructor(
        tripRepository: TripRepository,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mapData: StateFlow<MapData?> =
            combine(tripRepository.observeAllTracks(), tripRepository.observeAllHazards()) { tracks, hazards ->
                MapData.build(tracks, hazards)
            }
                .flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val viewport = MutableStateFlow<GeoBounds?>(null)
        private val selectedHazardId = MutableStateFlow<String?>(null)

        val uiState: StateFlow<PersonalMapUiState> =
            combine(mapData, viewport, selectedHazardId) { data, visible, hazardId ->
                if (data == null) {
                    PersonalMapUiState()
                } else {
                    PersonalMapUiState(
                        isLoading = false,
                        tripCount = data.tracks.size,
                        trackFeatures = data.trackFeatures,
                        hazardFeatures = data.hazardFeatures,
                        dataBounds = data.bounds,
                        summary = data.summary(visible),
                        selectedHazard = hazardId?.let { data.hazardsById[it] },
                    )
                }
            }
                .flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PersonalMapUiState())

        fun onViewportChanged(bounds: GeoBounds) {
            viewport.value = bounds
        }

        fun onHazardSelected(hazardId: String) {
            selectedHazardId.value = hazardId
        }

        fun onHazardSheetDismissed() {
            selectedHazardId.value = null
        }

        private class TrackInfo(
            val track: TripTrack,
            val bounds: GeoBounds?,
        )

        private class MapData(
            val tracks: List<TrackInfo>,
            val hazards: List<RecordedHazard>,
            val hazardsById: Map<String, RecordedHazard>,
            val trackFeatures: FeatureCollection,
            val hazardFeatures: FeatureCollection,
            val bounds: GeoBounds?,
        ) {
            fun summary(visible: GeoBounds?): MapViewSummary {
                val visibleTracks = if (visible == null) tracks else tracks.filter { it.bounds?.intersects(visible) == true }
                val visibleHazards = if (visible == null) hazards else hazards.filter { visible.contains(it.lat, it.lon) }
                return MapViewSummary(
                    tripCount = visibleTracks.size,
                    minorCount = visibleHazards.count { it.severity == Severity.MINOR },
                    moderateCount = visibleHazards.count { it.severity == Severity.MODERATE },
                    severeCount = visibleHazards.count { it.severity == Severity.SEVERE },
                )
            }

            companion object {
                fun build(
                    tracks: List<TripTrack>,
                    hazards: List<RecordedHazard>,
                ): MapData {
                    val trackInfos = tracks.map { TrackInfo(it, GeoBounds.of(it.points, { p -> p.lat }, { p -> p.lon })) }
                    val hazardBounds = GeoBounds.of(hazards, { it.lat }, { it.lon })
                    return MapData(
                        tracks = trackInfos,
                        hazards = hazards,
                        hazardsById = hazards.associateBy { it.id },
                        trackFeatures = RoadQualityGeoJson.trackFeatures(tracks),
                        hazardFeatures = RoadQualityGeoJson.hazardFeatures(hazards),
                        bounds = GeoBounds.union(trackInfos.map { it.bounds } + hazardBounds),
                    )
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

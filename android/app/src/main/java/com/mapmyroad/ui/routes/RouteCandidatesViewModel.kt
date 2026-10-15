package com.mapmyroad.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.data.routing.CurrentLocationSource
import com.mapmyroad.data.routing.RoutingSession
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.di.DefaultDispatcher
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.RouteOptions
import com.mapmyroad.domain.model.RouteQuery
import com.mapmyroad.domain.repository.RouteRepository
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.map.GeoPoint
import com.mapmyroad.map.Polyline6
import com.mapmyroad.ui.AppRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where the route origin (the current location) stands. */
enum class OriginStatus {
    /** Precise location is not granted yet; the screen explains why it is needed. */
    NEEDS_PERMISSION,
    LOCATING,

    /** Location is off or no fix arrived. */
    UNAVAILABLE,
    READY,
}

enum class RouteError {
    OFFLINE,
    UNAVAILABLE,
    NO_ROUTE,
    OUTSIDE_AREA,
    TOO_FAR,
    GENERIC,
}

/** A decoded candidate line for the map. */
class RouteLine(
    val candidateId: String,
    val points: List<GeoPoint>,
)

data class RouteCandidatesUiState(
    val destinationName: String?,
    val destination: GeoPoint,
    val origin: GeoPoint? = null,
    val originStatus: OriginStatus = OriginStatus.NEEDS_PERMISSION,
    /** Null until settings are read; see [needsVehicle]. */
    val vehicleType: VehicleType? = null,
    val needsVehicle: Boolean = false,
    /** Slider position, 0 = prefer comfort (left) to 1 = prefer speed (right). */
    val sliderPosition: Float = RoutePreference.toSliderPosition(RoutePreference.DEFAULT),
    /** The last good answer. Kept while a new request loads, so the screen never goes blank (UI_UX section 8). */
    val options: RouteOptions? = null,
    val lines: List<RouteLine> = emptyList(),
    val selectedId: String? = null,
    val isLoading: Boolean = false,
    val error: RouteError? = null,
    val isTypicalEtaExplainerVisible: Boolean = false,
)

private data class RequestInput(
    val origin: GeoPoint,
    val vehicleType: VehicleType,
    val preference: Double,
    val attempt: Int,
)

/**
 * Route candidates (S8, M5-8, PRD RT-2, RT-3): origin is the current location, destination comes from search.
 * Requests are re-sent when the preference slider settles (500 ms), keeping the previous answer visible. Nothing
 * here starts navigation; Start arrives with in-app navigation in v3. Coordinates are never logged.
 */
@HiltViewModel
class RouteCandidatesViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val routeRepository: RouteRepository,
        private val settingsRepository: SettingsRepository,
        private val locationSource: CurrentLocationSource,
        private val routingSession: RoutingSession,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val destination: GeoPoint =
            GeoPoint(
                lat = savedStateHandle.coordinateArg(AppRoutes.DESTINATION_LAT_ARG),
                lon = savedStateHandle.coordinateArg(AppRoutes.DESTINATION_LON_ARG),
            )

        private val state =
            MutableStateFlow(
                RouteCandidatesUiState(
                    destinationName = savedStateHandle.get<String>(AppRoutes.DESTINATION_NAME_ARG)?.takeIf { it.isNotBlank() },
                    destination = destination,
                ),
            )

        private val requestedPreference = MutableStateFlow(RoutePreference.DEFAULT)
        private val attempt = MutableStateFlow(0)
        private var preferenceJob: Job? = null
        private var locateJob: Job? = null

        /** True once the user tapped a card; then a new answer keeps that card selected if it still exists. */
        private var hasUserSelected = false

        val uiState: StateFlow<RouteCandidatesUiState> =
            combine(state, routingSession.isTypicalEtaExplained) { current, explained ->
                current.copy(isTypicalEtaExplainerVisible = !explained && current.options != null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), state.value)

        init {
            viewModelScope.launch {
                settingsRepository.settings
                    .map { it.vehicleType }
                    .distinctUntilChanged()
                    .collect { vehicle -> state.update { it.copy(vehicleType = vehicle, needsVehicle = vehicle == null) } }
            }
            viewModelScope.launch {
                val origins = state.map { it.origin }.distinctUntilChanged().filterNotNull()
                val vehicles = state.map { it.vehicleType }.distinctUntilChanged().filterNotNull()
                combine(origins, vehicles, requestedPreference, attempt) { origin, vehicle, preference, attemptCount ->
                    RequestInput(origin, vehicle, preference, attemptCount)
                }.collectLatest { input -> request(input) }
            }
        }

        /** Called when the screen sees precise location is granted (on start, after the prompt or on resume). */
        fun onLocationPermissionGranted() {
            val current = state.value
            if (current.originStatus == OriginStatus.READY || current.originStatus == OriginStatus.LOCATING) return
            locate()
        }

        fun onLocationPermissionMissing() {
            if (state.value.origin == null) state.update { it.copy(originStatus = OriginStatus.NEEDS_PERMISSION) }
        }

        fun onRetryLocation() {
            locate()
        }

        fun onVehicleSelected(vehicleType: VehicleType) {
            viewModelScope.launch { settingsRepository.setVehicleType(vehicleType) }
        }

        fun onSliderChange(position: Float) {
            state.update { it.copy(sliderPosition = position) }
            preferenceJob?.cancel()
            preferenceJob =
                viewModelScope.launch {
                    delay(PREFERENCE_DEBOUNCE_MS)
                    requestedPreference.value = RoutePreference.fromSliderPosition(position)
                }
        }

        fun onCandidateSelected(candidateId: String) {
            hasUserSelected = true
            state.update { it.copy(selectedId = candidateId) }
        }

        fun onRetry() {
            attempt.value += 1
        }

        fun onTypicalEtaExplainerDismissed() {
            routingSession.markTypicalEtaExplained()
        }

        private fun locate() {
            if (!locationSource.hasFineLocationPermission()) {
                onLocationPermissionMissing()
                return
            }
            locateJob?.cancel()
            state.update { it.copy(originStatus = OriginStatus.LOCATING) }
            locateJob =
                viewModelScope.launch {
                    val point = locationSource.currentLocation()
                    state.update {
                        if (point == null) {
                            it.copy(originStatus = OriginStatus.UNAVAILABLE)
                        } else {
                            it.copy(origin = point, originStatus = OriginStatus.READY)
                        }
                    }
                }
        }

        private suspend fun request(input: RequestInput) {
            state.update { it.copy(isLoading = true, error = null) }
            val query =
                RouteQuery(
                    originLat = input.origin.lat,
                    originLon = input.origin.lon,
                    destinationLat = destination.lat,
                    destinationLon = destination.lon,
                    vehicleType = input.vehicleType,
                    preference = input.preference,
                )
            when (val result = routeRepository.routes(query)) {
                is ApiResult.Success -> {
                    val options = result.value
                    val lines =
                        withContext(defaultDispatcher) {
                            options.candidates.map { RouteLine(it.id, Polyline6.decode(it.encodedPolyline6)) }
                        }
                    state.update { current ->
                        val keepSelection = hasUserSelected && options.candidates.any { it.id == current.selectedId }
                        val selected =
                            if (keepSelection) {
                                current.selectedId
                            } else {
                                (options.candidates.firstOrNull { it.isRecommended } ?: options.candidates.firstOrNull())?.id
                            }
                        current.copy(
                            options = options,
                            lines = lines,
                            selectedId = selected,
                            isLoading = false,
                            error = if (options.candidates.isEmpty()) RouteError.NO_ROUTE else null,
                        )
                    }
                }
                is ApiResult.Failure ->
                    state.update { it.copy(isLoading = false, error = result.error.toRouteError()) }
            }
        }

        private fun ApiError.toRouteError(): RouteError? =
            when (this) {
                ApiError.Network -> RouteError.OFFLINE
                ApiError.Unavailable -> RouteError.UNAVAILABLE
                is ApiError.Rejected ->
                    when (code) {
                        "NO_ROUTE" -> RouteError.NO_ROUTE
                        "UNSUPPORTED_REGION" -> RouteError.OUTSIDE_AREA
                        "TOO_FAR" -> RouteError.TOO_FAR
                        else -> RouteError.GENERIC
                    }
                // The app-wide update screen takes over (UI_UX_GUIDELINES.md section 8).
                ApiError.UpdateRequired -> null
                else -> RouteError.GENERIC
            }

        private companion object {
            const val PREFERENCE_DEBOUNCE_MS = 500L
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Navigation encodes coordinates with [AppRoutes.routeCandidates]; a malformed value is a programming error. */
private fun SavedStateHandle.coordinateArg(key: String): Double =
    requireNotNull(get<String>(key)?.toDoubleOrNull()) { "Route candidates opened without a valid destination" }

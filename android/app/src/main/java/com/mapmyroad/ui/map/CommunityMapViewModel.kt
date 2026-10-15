package com.mapmyroad.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.BuildConfig
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.repository.CommunityRepository
import com.mapmyroad.map.CommunityCoverage
import com.mapmyroad.map.CommunityTiles
import com.mapmyroad.map.CommunityViewport
import com.mapmyroad.map.MapMode
import com.mapmyroad.map.TileHazard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The community hazard detail sheet (S7). Opens at once with the tile feature data; [details] arrive from
 * `GET /v1/hazards/{id}` when the device can reach the API. No feedback actions here (ADR-0013).
 */
@Immutable
data class CommunityHazardSheetState(
    val tile: TileHazard,
    val details: CommunityHazard? = null,
    val isLoadingDetails: Boolean = true,
    val detailsUnavailable: Boolean = false,
) {
    override fun toString(): String = "CommunityHazardSheetState(id=${tile.id})"
}

@Immutable
data class CommunityMapUiState(
    val mode: MapMode = MapMode.PERSONAL,
    /** Last idle measurement of the community layers; null until the map has been idle in community mode. */
    val viewport: CommunityViewport? = null,
    val sheet: CommunityHazardSheetState? = null,
) {
    val coverage: CommunityCoverage get() = CommunityCoverage.of(viewport)
}

/**
 * Community mode of the map (M5-5): the selected mode, the "Limited community data here" banner and the community
 * hazard sheet. Viewing the community map needs no opt-in, the tiles are public data. Community hazards are kept in
 * memory only, never on disk.
 */
@HiltViewModel
class CommunityMapViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val communityRepository: CommunityRepository,
        private val deviceSession: DeviceSession,
    ) : ViewModel() {
        /** Tile URL template for the vector source (API_SPEC.md 5.4). */
        val tileUrlTemplate: String = CommunityTiles.tileUrlTemplate(BuildConfig.API_BASE_URL)

        private val mode: StateFlow<MapMode> = savedStateHandle.getStateFlow(KEY_MODE, MapMode.PERSONAL)
        private val viewport = MutableStateFlow<CommunityViewport?>(null)
        private val sheet = MutableStateFlow<CommunityHazardSheetState?>(null)
        private var detailsJob: Job? = null

        val uiState: StateFlow<CommunityMapUiState> =
            combine(mode, viewport, sheet) { currentMode, currentViewport, currentSheet ->
                CommunityMapUiState(mode = currentMode, viewport = currentViewport, sheet = currentSheet)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CommunityMapUiState())

        fun onModeSelected(newMode: MapMode) {
            if (mode.value == newMode) return
            savedStateHandle[KEY_MODE] = newMode
            // The banner is measured again once the map is idle in the new mode.
            viewport.value = null
            if (newMode != MapMode.COMMUNITY) onCommunityHazardSheetDismissed()
        }

        fun onCommunityViewport(measured: CommunityViewport) {
            if (mode.value == MapMode.COMMUNITY) viewport.value = measured
        }

        fun onCommunityHazardSelected(hazard: TileHazard) {
            detailsJob?.cancel()
            sheet.value = CommunityHazardSheetState(tile = hazard)
            detailsJob =
                viewModelScope.launch {
                    // Viewing the community map needs no backend identity (tiles are public). Only installs that
                    // already registered (contributors, or users of search and routes) fetch the details, so a tap
                    // never creates a server-side install ID on its own (DeviceSession, PRD PV-1).
                    if (!deviceSession.isRegistered()) {
                        sheet.update { current ->
                            if (current == null || current.tile.id != hazard.id) current else current.copy(isLoadingDetails = false)
                        }
                        return@launch
                    }
                    val result = communityRepository.hazard(hazard.id)
                    sheet.update { current ->
                        if (current == null || current.tile.id != hazard.id) return@update current
                        when (result) {
                            is ApiResult.Success -> current.copy(details = result.value, isLoadingDetails = false)
                            // The sheet keeps showing the tile data (severity, confidence, last seen).
                            is ApiResult.Failure -> current.copy(isLoadingDetails = false, detailsUnavailable = true)
                        }
                    }
                }
        }

        fun onCommunityHazardSheetDismissed() {
            detailsJob?.cancel()
            detailsJob = null
            sheet.value = null
        }

        private companion object {
            const val KEY_MODE = "map_mode"
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

package com.mapmyroad.ui.summary.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.Severity
import com.mapmyroad.di.DefaultDispatcher
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.domain.repository.CommunityRepository
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** State of one hazard row of the post-trip review. */
sealed interface ReviewRowStatus {
    data object Pending : ReviewRowStatus

    data class Sending(
        val action: HazardFeedbackAction,
    ) : ReviewRowStatus

    data class Sent(
        val action: HazardFeedbackAction,
    ) : ReviewRowStatus

    /** Sending failed in a way that may work later; the buttons stay available to try again. */
    data class Failed(
        val action: HazardFeedbackAction,
    ) : ReviewRowStatus

    /** The server will not take an answer for this hazard (gone, hidden, or the trip is too old). */
    data object Rejected : ReviewRowStatus
}

/** One row: no coordinates, only what the card shows. */
@Immutable
data class HazardReviewRow(
    val hazardId: String,
    val severity: Severity,
    /** Distance from the trip start, shown as "at 2.3 km". */
    val alongTrackM: Double,
    val status: ReviewRowStatus,
)

sealed interface HazardReviewUiState {
    /** Nothing to review, not contributing, a trip is recording, skipped, or the hazards could not be loaded. */
    data object Hidden : HazardReviewUiState

    data class Ready(
        val rows: List<HazardReviewRow>,
    ) : HazardReviewUiState {
        val isComplete: Boolean get() = rows.all { it.status is ReviewRowStatus.Sent || it.status == ReviewRowStatus.Rejected }
    }
}

/**
 * Post-trip hazard review (S12, M5-6, PRD CM-4): "You passed N hazards we know about. Still there?" Feedback is only
 * collected here, after the trip, never while moving (ADR-0013, UI_UX_GUIDELINES.md section 6). Shown only to
 * contributors (PRD CM-1), for finished trips recent enough for the server's 14-day limit, and hidden while any trip
 * is recording. The trip id is set with [setTripId], like [com.mapmyroad.ui.map.TripRouteMapViewModel].
 *
 * Community hazards and the feedback locations stay in memory only. Nothing here logs coordinates.
 */
@HiltViewModel
class HazardReviewViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val settingsRepository: SettingsRepository,
        private val communityRepository: CommunityRepository,
        tripSessionController: TripSessionController,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private var tripId: String? = null
        private var loadJob: Job? = null

        /** Feedback location and time per hazard id. Only touched on the main thread. */
        private var candidates: Map<String, ReviewCandidate> = emptyMap()
        private val rows = MutableStateFlow<List<HazardReviewRow>>(emptyList())
        private val skipped = MutableStateFlow(false)

        val uiState: StateFlow<HazardReviewUiState> =
            combine(
                rows,
                skipped,
                tripSessionController.state,
                settingsRepository.settings.map { it.contributionEnabled },
            ) { currentRows, isSkipped, session, isContributing ->
                if (currentRows.isEmpty() || isSkipped || session != TripSessionState.Idle || !isContributing) {
                    HazardReviewUiState.Hidden
                } else {
                    HazardReviewUiState.Ready(currentRows)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HazardReviewUiState.Hidden)

        fun setTripId(id: String) {
            if (tripId == id) return
            tripId = id
            loadJob?.cancel()
            candidates = emptyMap()
            rows.value = emptyList()
            skipped.value = false
            loadJob = viewModelScope.launch { load(id) }
        }

        fun onAnswer(
            hazardId: String,
            action: HazardFeedbackAction,
        ) {
            val candidate = candidates[hazardId] ?: return
            val status = rows.value.firstOrNull { it.hazardId == hazardId }?.status ?: return
            if (status is ReviewRowStatus.Sending || status is ReviewRowStatus.Sent || status == ReviewRowStatus.Rejected) return
            setRowStatus(hazardId, ReviewRowStatus.Sending(action))
            viewModelScope.launch {
                val match = candidate.match
                val result = communityRepository.submitFeedback(hazardId, action, match.lat, match.lon, match.tMs)
                val newStatus =
                    when (result) {
                        is ApiResult.Success -> ReviewRowStatus.Sent(action)
                        is ApiResult.Failure ->
                            if (HazardReviewPlanner.isRetryable(result.error)) ReviewRowStatus.Failed(action) else ReviewRowStatus.Rejected
                    }
                setRowStatus(hazardId, newStatus)
            }
        }

        fun onSkipAll() {
            skipped.value = true
        }

        private suspend fun load(id: String) {
            // Waits until the user contributes; nothing is requested for people who did not opt in (PRD CM-1).
            settingsRepository.settings.map { it.contributionEnabled }.first { it }
            val trip = tripRepository.observeTrip(id).first() ?: return
            val nowMs = System.currentTimeMillis()
            if (!HazardReviewPlanner.isReviewable(trip.endedAtMs, nowMs)) return
            val points = tripRepository.observeTripPoints(id).first()
            val boxes = withContext(defaultDispatcher) { HazardReviewPlanner.queryBoxes(points) }
            val hazards = ArrayList<CommunityHazard>()
            for (box in boxes) {
                when (val result = communityRepository.hazardsIn(box.south, box.west, box.north, box.east)) {
                    is ApiResult.Success -> hazards.addAll(result.value)
                    is ApiResult.Failure -> {
                        // The review is optional: on failure the card is simply not shown. No coordinates logged.
                        Timber.i("Hazard review skipped: hazards request failed (%s)", result.error::class.simpleName)
                        return
                    }
                }
            }
            val found = withContext(defaultDispatcher) { HazardReviewPlanner.candidates(points, hazards, nowMs) }
            candidates = found.associateBy { it.hazard.id }
            rows.value =
                found.map {
                    HazardReviewRow(
                        hazardId = it.hazard.id,
                        severity = it.hazard.severity,
                        alongTrackM = it.match.alongTrackM,
                        status = ReviewRowStatus.Pending,
                    )
                }
        }

        private fun setRowStatus(
            hazardId: String,
            status: ReviewRowStatus,
        ) {
            rows.update { current -> current.map { if (it.hazardId == hazardId) it.copy(status = status) else it } }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

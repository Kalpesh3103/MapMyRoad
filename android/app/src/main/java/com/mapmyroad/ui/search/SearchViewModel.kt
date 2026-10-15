package com.mapmyroad.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.data.routing.CurrentLocationSource
import com.mapmyroad.data.routing.RoutingMappers
import com.mapmyroad.data.routing.RoutingSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.Place
import com.mapmyroad.domain.repository.RouteRepository
import com.mapmyroad.map.GeoPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the result area under the search field shows (UI_UX S8). */
sealed interface SearchStatus {
    /** Fewer than 3 characters: recent destinations or a hint. */
    data object Idle : SearchStatus

    data object Loading : SearchStatus

    data class Results(
        val places: List<Place>,
    ) : SearchStatus

    data object Empty : SearchStatus

    data class Failed(
        val error: SearchError,
    ) : SearchStatus
}

enum class SearchError {
    OFFLINE,
    UNAVAILABLE,
    GENERIC,
}

data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
    val recentDestinations: List<Place> = emptyList(),
)

/**
 * Destination search (M5-7, PRD RT-1): 350 ms debounce, at least 3 characters, bias on the current location when
 * location access is already granted (this screen never asks for it), and a small in-memory LRU cache. Search text
 * is never logged.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val routeRepository: RouteRepository,
        private val locationSource: CurrentLocationSource,
        private val routingSession: RoutingSession,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val status = MutableStateFlow<SearchStatus>(SearchStatus.Idle)
        private val retryCount = MutableStateFlow(0)
        private val cache = QueryLruCache<SearchCacheKey, List<Place>>(CACHE_SIZE)

        /** Looked up once per screen; null when there is no permission or no fix. */
        private var bias: GeoPoint? = null
        private var isBiasResolved = false

        val uiState: StateFlow<SearchUiState> =
            combine(query, status, routingSession.recentDestinations) { text, currentStatus, recents ->
                SearchUiState(query = text, status = currentStatus, recentDestinations = recents)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

        init {
            viewModelScope.launch {
                // No distinctUntilChanged: typing "Pune", "Pu", "Pune" quickly must search again after the reset
                // to Idle in onQueryChange. Repeats are answered from the cache.
                val debouncedQuery = query.debounce(DEBOUNCE_MS).map(RoutingMappers::normalizeQuery)
                // collectLatest cancels a search still in flight when the user keeps typing.
                combine(debouncedQuery, retryCount) { text, _ -> text }
                    .collectLatest { text -> runSearch(text) }
            }
        }

        fun onQueryChange(text: String) {
            query.value = text
            // Short queries clear results at once instead of after the debounce.
            if (RoutingMappers.normalizeQuery(text).length < RoutingMappers.MIN_QUERY_LENGTH) {
                status.value = SearchStatus.Idle
            }
        }

        fun onRetry() {
            retryCount.value += 1
        }

        /** Remembers the chosen destination in memory only (see [RoutingSession]). */
        fun onPlaceChosen(place: Place) {
            routingSession.addRecent(place)
        }

        private suspend fun runSearch(text: String) {
            if (text.length < RoutingMappers.MIN_QUERY_LENGTH) {
                status.value = SearchStatus.Idle
                return
            }
            val language = SearchLanguage.of(Locale.getDefault())
            val point = resolveBias()
            val biasLat = point?.lat?.let(RoutingMappers::roundBias)
            val biasLon = point?.lon?.let(RoutingMappers::roundBias)
            val key = SearchCacheKey.of(text, language, biasLat, biasLon)
            cache[key]?.let { cached ->
                status.value = cached.toStatus()
                return
            }
            status.value = SearchStatus.Loading
            val result = routeRepository.search(text, biasLat, biasLon, language)
            if (result is ApiResult.Success) cache.put(key, result.value)
            // The field may already hold a shorter query; then the Idle state set in onQueryChange wins.
            if (RoutingMappers.normalizeQuery(query.value) != text) return
            status.value =
                when (result) {
                    is ApiResult.Success -> result.value.toStatus()
                    is ApiResult.Failure -> result.error.toStatus()
                }
        }

        private suspend fun resolveBias(): GeoPoint? {
            if (!isBiasResolved && locationSource.hasAnyLocationPermission()) {
                bias = locationSource.currentLocation()
                isBiasResolved = true
            }
            return bias
        }

        private fun List<Place>.toStatus(): SearchStatus = if (isEmpty()) SearchStatus.Empty else SearchStatus.Results(this)

        private fun ApiError.toStatus(): SearchStatus =
            when (this) {
                ApiError.Network -> SearchStatus.Failed(SearchError.OFFLINE)
                ApiError.Unavailable -> SearchStatus.Failed(SearchError.UNAVAILABLE)
                // The app-wide update screen takes over (UI_UX_GUIDELINES.md section 8); nothing to show here.
                ApiError.UpdateRequired -> SearchStatus.Idle
                else -> SearchStatus.Failed(SearchError.GENERIC)
            }

        private companion object {
            const val DEBOUNCE_MS = 350L
            const val CACHE_SIZE = 20
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

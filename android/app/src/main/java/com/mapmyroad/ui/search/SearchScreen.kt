package com.mapmyroad.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.domain.model.Place

/**
 * Destination search, the top part of S8 (M5-7, PRD RT-1). Opened from the map; picking a place opens the route
 * candidates. Planning happens before a trip; the screen has no dialogs, so it is safe to show at any time.
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onPlaceChosen: (Place) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::onRetry,
        onPlaceChosen = { place ->
            viewModel.onPlaceChosen(place)
            onPlaceChosen(place)
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPlaceChosen: (Place) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text(text = stringResource(R.string.search_hint)) },
                    label = { Text(text = stringResource(R.string.search_label)) },
                    leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.search_clear),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
        ) {
            when (val status = uiState.status) {
                SearchStatus.Idle -> IdleContent(recents = uiState.recentDestinations, onPlaceChosen = onPlaceChosen)
                SearchStatus.Loading ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(24.dp),
                    )
                is SearchStatus.Results -> PlaceList(places = status.places, onPlaceChosen = onPlaceChosen)
                SearchStatus.Empty -> MessageText(text = stringResource(R.string.search_empty))
                is SearchStatus.Failed -> ErrorContent(error = status.error, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun IdleContent(
    recents: List<Place>,
    onPlaceChosen: (Place) -> Unit,
) {
    if (recents.isEmpty()) {
        MessageText(text = stringResource(R.string.search_idle_hint))
        return
    }
    Text(
        text = stringResource(R.string.search_recent_title),
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
    )
    PlaceList(places = recents, onPlaceChosen = onPlaceChosen)
}

@Composable
private fun PlaceList(
    places: List<Place>,
    onPlaceChosen: (Place) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // Index keys: two results can share a name and position (for example two entrances of one place).
        items(count = places.size) { index ->
            val place = places[index]
            ListItem(
                headlineContent = { Text(text = place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent =
                    if (place.displayName != place.name) {
                        { Text(text = place.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                leadingContent = { Icon(imageVector = Icons.Filled.Place, contentDescription = null) },
                modifier = Modifier.clickable { onPlaceChosen(place) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ErrorContent(
    error: SearchError,
    onRetry: () -> Unit,
) {
    val message =
        when (error) {
            SearchError.OFFLINE -> stringResource(R.string.search_error_offline)
            SearchError.UNAVAILABLE -> stringResource(R.string.search_error_unavailable)
            SearchError.GENERIC -> stringResource(R.string.search_error_generic)
        }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.search_retry))
        }
    }
}

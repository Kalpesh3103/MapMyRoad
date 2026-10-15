package com.mapmyroad.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.ui.onboarding.OnboardingRoute
import com.mapmyroad.ui.update.UpdateRequiredScreen
import com.mapmyroad.ui.update.openPlayStoreListing

/**
 * Root gate: onboarding until it is completed, then the main shell. When "Delete all data" resets the settings,
 * onboarding completed becomes false and this gate shows onboarding again (S11). When the server answers `426`,
 * the update screen covers everything once no trip is active (see [resolveAppGate]).
 */
@Composable
fun MapMyRoadRoot(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val gateState by viewModel.gateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when (gateState) {
        AppGateState.Loading -> Surface(modifier = modifier.fillMaxSize()) {}
        AppGateState.Onboarding -> OnboardingRoute(modifier = modifier)
        AppGateState.Main -> MapMyRoadNavHost(finishedTrips = viewModel.finishedTrips, modifier = modifier)
        AppGateState.UpdateRequired -> UpdateRequiredScreen(onUpdate = { context.openPlayStoreListing() }, modifier = modifier)
    }
}

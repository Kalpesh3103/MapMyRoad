package com.mapmyroad.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.ui.onboarding.OnboardingRoute

/**
 * Root gate: onboarding until it is completed, then the main shell. When "Delete all data" resets the settings,
 * onboarding completed becomes false and this gate shows onboarding again (S11).
 */
@Composable
fun MapMyRoadRoot(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val gateState by viewModel.gateState.collectAsStateWithLifecycle()
    when (gateState) {
        AppGateState.Loading -> Surface(modifier = modifier.fillMaxSize()) {}
        AppGateState.Onboarding -> OnboardingRoute(modifier = modifier)
        AppGateState.Main -> MapMyRoadNavHost(finishedTrips = viewModel.finishedTrips, modifier = modifier)
    }
}

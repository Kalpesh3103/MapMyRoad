package com.mapmyroad.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.ui.components.BackTopAppBar
import com.mapmyroad.ui.components.ExternalLinks
import com.mapmyroad.ui.components.openWebPage
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Privacy and data (S11), mirroring the v1 rows of the Data safety table (PRD section 8.1). */
@Composable
fun PrivacyRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PrivacyScreen(
        state = state,
        onBack = onBack,
        onOpenPrivacyPolicy = { context.openWebPage(ExternalLinks.PRIVACY_POLICY_URL) },
        onDeleteAllData = viewModel::onDeleteAllData,
        modifier = modifier,
    )
}

@Composable
fun PrivacyScreen(
    state: PrivacyUiState,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onDeleteAllData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isConfirmDialogVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopAppBar(title = stringResource(R.string.privacy_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Heading(text = stringResource(R.string.privacy_collected_title))
            PrivacyStatement(R.string.privacy_location_title, R.string.privacy_location_body)
            PrivacyStatement(R.string.privacy_motion_title, R.string.privacy_motion_body)
            PrivacyStatement(R.string.privacy_on_phone_title, R.string.privacy_on_phone_body)
            PrivacyStatement(R.string.privacy_diagnostics_title, R.string.privacy_diagnostics_body)
            PrivacyStatement(R.string.privacy_no_account_title, R.string.privacy_no_account_body)
            OutlinedButton(
                onClick = onOpenPrivacyPolicy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.privacy_policy_link))
            }
            HorizontalDivider()
            Heading(text = stringResource(R.string.privacy_delete_title))
            Text(text = stringResource(R.string.privacy_delete_body), style = MaterialTheme.typography.bodyLarge)
            if (state.isTripActive) {
                Text(
                    text = stringResource(R.string.privacy_delete_blocked_by_trip),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.hasDeleteFailed) {
                Text(
                    text = stringResource(R.string.common_delete_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { isConfirmDialogVisible = true },
                enabled = !state.isTripActive && !state.isDeleting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            if (state.isDeleting) R.string.privacy_deleting else R.string.privacy_delete_button,
                        ),
                )
            }
        }
    }

    if (isConfirmDialogVisible) {
        AlertDialog(
            onDismissRequest = { isConfirmDialogVisible = false },
            title = { Text(text = stringResource(R.string.privacy_delete_dialog_title)) },
            text = { Text(text = stringResource(R.string.privacy_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmDialogVisible = false
                        onDeleteAllData()
                    },
                ) {
                    Text(text = stringResource(R.string.privacy_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmDialogVisible = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun PrivacyStatement(
    titleRes: Int,
    bodyRes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrivacyScreenPreview() {
    MapMyRoadTheme {
        PrivacyScreen(state = PrivacyUiState(), onBack = {}, onOpenPrivacyPolicy = {}, onDeleteAllData = {})
    }
}

@Preview(name = "Trip recording", showBackground = true)
@Composable
private fun PrivacyScreenRecordingPreview() {
    MapMyRoadTheme {
        PrivacyScreen(
            state = PrivacyUiState(isTripActive = true),
            onBack = {},
            onOpenPrivacyPolicy = {},
            onDeleteAllData = {},
        )
    }
}

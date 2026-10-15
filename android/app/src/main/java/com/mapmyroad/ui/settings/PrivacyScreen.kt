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

/** Privacy and data (S11), mirroring the v2 rows of the Data safety table (PRD section 8.1). */
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
        // This screen is opened from Settings, so going back is the way to the contribution toggle (owned there).
        onOpenSettings = onBack,
        onDeleteMyData = viewModel::onDeleteMyData,
        onDeleteOnlyOnThisPhone = viewModel::onDeleteOnlyOnThisPhone,
        modifier = modifier,
    )
}

@Composable
fun PrivacyScreen(
    state: PrivacyUiState,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteMyData: () -> Unit,
    onDeleteOnlyOnThisPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isConfirmDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isLocalOnlyDialogVisible by rememberSaveable { mutableStateOf(false) }

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
            PrivacyStatement(R.string.privacy_sharing_title, R.string.privacy_sharing_body)
            PrivacyStatement(R.string.privacy_install_id_title, R.string.privacy_install_id_body)
            PrivacyStatement(R.string.privacy_feedback_title, R.string.privacy_feedback_body)
            PrivacyStatement(R.string.privacy_search_title, R.string.privacy_search_body)
            PrivacyStatement(R.string.privacy_map_title, R.string.privacy_map_body)
            PrivacyStatement(R.string.privacy_diagnostics_title, R.string.privacy_diagnostics_body)
            PrivacyStatement(R.string.privacy_never_title, R.string.privacy_never_body)
            PrivacyStatement(R.string.privacy_no_account_title, R.string.privacy_no_account_body)
            ContributionStatus(isEnabled = state.isContributionEnabled, onOpenSettings = onOpenSettings)
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
                ErrorText(text = stringResource(R.string.privacy_delete_blocked_by_trip))
            }
            deleteErrorRes(state.deleteStatus)?.let { errorRes -> ErrorText(text = stringResource(errorRes)) }
            Button(
                onClick = {
                    // A failed attempt is retried directly; the user already confirmed it.
                    if (state.deleteStatus == DeleteStatus.IDLE) {
                        isConfirmDialogVisible = true
                    } else {
                        onDeleteMyData()
                    }
                },
                enabled = state.canDelete,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(text = stringResource(deleteButtonRes(state.deleteStatus)))
            }
            if (state.canDeleteOnlyOnThisPhone) {
                OutlinedButton(
                    onClick = { isLocalOnlyDialogVisible = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.privacy_delete_local_only))
                }
            }
        }
    }

    // Deleting is blocked during a trip, and no dialog is ever shown while one records (UI_UX_GUIDELINES.md section 6).
    if (isConfirmDialogVisible && state.canDelete) {
        DeleteConfirmDialog(
            titleRes = R.string.privacy_delete_dialog_title,
            bodyRes = if (state.isRegistered) R.string.privacy_delete_dialog_body_shared else R.string.privacy_delete_dialog_body,
            onConfirm = {
                isConfirmDialogVisible = false
                onDeleteMyData()
            },
            onDismiss = { isConfirmDialogVisible = false },
        )
    }
    if (isLocalOnlyDialogVisible && state.canDeleteOnlyOnThisPhone) {
        DeleteConfirmDialog(
            titleRes = R.string.privacy_delete_local_only_dialog_title,
            bodyRes = R.string.privacy_delete_local_only_dialog_body,
            onConfirm = {
                isLocalOnlyDialogVisible = false
                onDeleteOnlyOnThisPhone()
            },
            onDismiss = { isLocalOnlyDialogVisible = false },
        )
    }
}

private fun deleteErrorRes(status: DeleteStatus): Int? =
    when (status) {
        DeleteStatus.SERVER_UNREACHABLE -> R.string.privacy_delete_server_unreachable
        DeleteStatus.SERVER_FAILED -> R.string.privacy_delete_server_failed
        DeleteStatus.LOCAL_FAILED -> R.string.common_delete_failed
        DeleteStatus.IDLE, DeleteStatus.DELETING -> null
    }

private fun deleteButtonRes(status: DeleteStatus): Int =
    when (status) {
        DeleteStatus.IDLE -> R.string.privacy_delete_button
        DeleteStatus.DELETING -> R.string.privacy_deleting
        DeleteStatus.SERVER_UNREACHABLE, DeleteStatus.SERVER_FAILED, DeleteStatus.LOCAL_FAILED -> R.string.privacy_delete_retry
    }

@Composable
private fun DeleteConfirmDialog(
    titleRes: Int,
    bodyRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = { Text(text = stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.privacy_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ContributionStatus(
    isEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(if (isEnabled) R.string.privacy_contribution_on else R.string.privacy_contribution_off),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = stringResource(R.string.privacy_contribution_change))
        }
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
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun PrivacyScreenPreview() {
    MapMyRoadTheme {
        PreviewPrivacyScreen(state = PrivacyUiState(isRegistered = true, isContributionEnabled = true))
    }
}

@Preview(name = "Trip recording", showBackground = true)
@Composable
private fun PrivacyScreenRecordingPreview() {
    MapMyRoadTheme {
        PreviewPrivacyScreen(state = PrivacyUiState(isTripActive = true))
    }
}

@Preview(name = "Server unreachable", showBackground = true)
@Composable
private fun PrivacyScreenServerUnreachablePreview() {
    MapMyRoadTheme {
        PreviewPrivacyScreen(state = PrivacyUiState(isRegistered = true, deleteStatus = DeleteStatus.SERVER_UNREACHABLE))
    }
}

@Composable
private fun PreviewPrivacyScreen(state: PrivacyUiState) {
    PrivacyScreen(
        state = state,
        onBack = {},
        onOpenPrivacyPolicy = {},
        onOpenSettings = {},
        onDeleteMyData = {},
        onDeleteOnlyOnThisPhone = {},
    )
}

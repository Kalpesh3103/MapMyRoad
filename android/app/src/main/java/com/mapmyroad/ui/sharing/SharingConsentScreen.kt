package com.mapmyroad.ui.sharing

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.ui.components.BackTopAppBar
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Minimum touch target height for the buttons (UI_UX_GUIDELINES.md, large targets). */
private val BUTTON_MIN_HEIGHT = 48.dp

/**
 * One-time contribution consent (UI_UX_GUIDELINES.md section 13, S11; PRD CM-1, PV-1, PV-2, section 8.1). Shown full
 * screen from Settings before contribution is first turned on, and later as "What is shared". Never shown during a
 * trip (Settings is not reachable from the live trip screen).
 *
 * When [isContributing] is true the screen is informational and only offers Close.
 */
@Composable
fun SharingConsentScreen(
    isContributing: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopAppBar(title = stringResource(R.string.sharing_consent_title), onBack = onDismiss) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.sharing_consent_intro), style = MaterialTheme.typography.bodyLarge)
            ConsentSection(
                title = stringResource(R.string.sharing_consent_shared_title),
                body = stringResource(R.string.sharing_consent_shared_body),
            )
            ConsentSection(
                title = stringResource(R.string.sharing_consent_trim_title),
                body = stringResource(R.string.sharing_consent_trim_body),
            )
            ConsentSection(
                title = stringResource(R.string.sharing_consent_not_shared_title),
                body = stringResource(R.string.sharing_consent_not_shared_body),
            )
            ConsentSection(
                title = stringResource(R.string.sharing_consent_id_title),
                body = stringResource(R.string.sharing_consent_id_body),
            )
            ConsentSection(
                title = stringResource(R.string.sharing_consent_control_title),
                body = stringResource(R.string.sharing_consent_control_body),
            )
            if (isContributing) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_MIN_HEIGHT),
                ) {
                    Text(text = stringResource(R.string.sharing_consent_close))
                }
            } else {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_MIN_HEIGHT),
                ) {
                    Text(text = stringResource(R.string.sharing_consent_accept))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_MIN_HEIGHT),
                ) {
                    Text(text = stringResource(R.string.sharing_consent_decline))
                }
            }
        }
    }
}

@Composable
private fun ConsentSection(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun SharingConsentScreenPreview() {
    MapMyRoadTheme {
        SharingConsentScreen(isContributing = false, onAccept = {}, onDismiss = {})
    }
}

@Preview(name = "Contributing", showBackground = true)
@Composable
private fun SharingConsentScreenContributingPreview() {
    MapMyRoadTheme {
        SharingConsentScreen(isContributing = true, onAccept = {}, onDismiss = {})
    }
}

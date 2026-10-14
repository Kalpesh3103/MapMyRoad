package com.mapmyroad.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.BuildConfig
import com.mapmyroad.R
import com.mapmyroad.ui.components.BackTopAppBar
import com.mapmyroad.ui.components.ExternalLinks
import com.mapmyroad.ui.components.openWebPage
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** About: version, OpenStreetMap attribution (PRD PV-7) and an open source licenses note. */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AboutContent(
        versionName = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onOpenOsmCopyright = { context.openWebPage(ExternalLinks.OSM_COPYRIGHT_URL) },
        modifier = modifier,
    )
}

@Composable
private fun AboutContent(
    versionName: String,
    onBack: () -> Unit,
    onOpenOsmCopyright: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopAppBar(title = stringResource(R.string.about_title), onBack = onBack) },
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
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.about_map_data_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.about_map_data_body), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onOpenOsmCopyright, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.about_osm_copyright_link))
            }
            Text(
                text = stringResource(R.string.about_licenses_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.about_licenses_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutPreview() {
    MapMyRoadTheme {
        AboutContent(versionName = "0.1.0", onBack = {}, onOpenOsmCopyright = {})
    }
}

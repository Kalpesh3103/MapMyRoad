package com.mapmyroad.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.map.MapConfig

/**
 * Always-visible OpenStreetMap attribution (PRD PV-7, UI_UX_GUIDELINES.md section 12). MapLibre's own
 * attribution button stays enabled as well. Tapping opens the OSM copyright page.
 */
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val actionLabel = stringResource(R.string.map_attribution_action)
    Surface(
        modifier =
            modifier
                .padding(8.dp)
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = actionLabel, role = Role.Button) {
                    uriHandler.openUri(MapConfig.OSM_COPYRIGHT_URL)
                },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
    ) {
        Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .wrapContentHeight(),
            )
        }
    }
}

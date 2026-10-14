package com.mapmyroad.ui.map

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.map.RoadBand
import com.mapmyroad.ui.theme.MapMyRoadTheme
import com.mapmyroad.ui.theme.RoadQualityColors

/**
 * Collapsible legend (UI_UX_GUIDELINES.md section 4.2: always shown on map screens). Each band shows its line
 * style and label, each severity its marker shape and label, so the legend never relies on color alone.
 */
@Composable
internal fun MapLegend(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.map_legend_title))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription =
                        stringResource(if (expanded) R.string.map_legend_hide else R.string.map_legend_show),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RoadBand.entries.forEach { band -> BandRow(band) }
                    Severity.entries.forEach { severity -> SeverityRow(severity) }
                }
            }
        }
    }
}

@Composable
private fun BandRow(band: RoadBand) {
    val label = stringResource(band.labelRes())
    val style = stringResource(band.lineStyleRes())
    val description = stringResource(R.string.map_legend_item_description, label, style)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineSample(band = band, modifier = Modifier.size(width = 40.dp, height = 16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SeverityRow(severity: Severity) {
    val label = stringResource(severity.labelRes())
    val shape = stringResource(severity.shapeRes())
    val description = stringResource(R.string.map_legend_item_description, label, shape)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityIcon(severity = severity, size = 24.dp, modifier = Modifier.width(40.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The map marker of [severity] as a decorative image (callers provide the text label). */
@Composable
internal fun SeverityIcon(
    severity: Severity,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val bitmap =
        remember(severity, size, density) {
            MarkerBitmaps.create(severity, density, sizeDp = size.value).asImageBitmap()
        }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
}

/** A short line drawn like the map layer of [band]: solid, dashed, dotted and thicker, or thin. */
@Composable
private fun LineSample(
    band: RoadBand,
    modifier: Modifier = Modifier,
) {
    val color = band.color()
    Canvas(modifier = modifier) {
        val baseWidth = 4.dp.toPx()
        val strokeWidth =
            when (band) {
                RoadBand.POOR -> baseWidth * 1.4f
                RoadBand.NO_DATA -> baseWidth * 0.6f
                RoadBand.GOOD, RoadBand.FAIR -> baseWidth
            }
        // Same patterns as the map layers, which measure dashes in line widths.
        val pathEffect =
            when (band) {
                RoadBand.FAIR -> PathEffect.dashPathEffect(floatArrayOf(2f * strokeWidth, 1.5f * strokeWidth))
                RoadBand.POOR -> PathEffect.dashPathEffect(floatArrayOf(0.01f, 1.8f * strokeWidth))
                RoadBand.GOOD, RoadBand.NO_DATA -> null
            }
        val y = size.height / 2f
        drawLine(
            color = color,
            start = Offset(strokeWidth, y),
            end = Offset(size.width - strokeWidth, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
            pathEffect = pathEffect,
        )
    }
}

internal fun RoadBand.labelRes(): Int =
    when (this) {
        RoadBand.GOOD -> R.string.map_band_good
        RoadBand.FAIR -> R.string.map_band_fair
        RoadBand.POOR -> R.string.map_band_poor
        RoadBand.NO_DATA -> R.string.map_band_no_data
    }

private fun RoadBand.lineStyleRes(): Int =
    when (this) {
        RoadBand.GOOD -> R.string.map_line_solid
        RoadBand.FAIR -> R.string.map_line_dashed
        RoadBand.POOR -> R.string.map_line_dotted
        RoadBand.NO_DATA -> R.string.map_line_thin
    }

private fun RoadBand.color(): Color =
    when (this) {
        RoadBand.GOOD -> RoadQualityColors.Good
        RoadBand.FAIR -> RoadQualityColors.Fair
        RoadBand.POOR -> RoadQualityColors.Poor
        RoadBand.NO_DATA -> RoadQualityColors.NoData
    }

internal fun Severity.labelRes(): Int =
    when (this) {
        Severity.MINOR -> R.string.map_severity_minor
        Severity.MODERATE -> R.string.map_severity_moderate
        Severity.SEVERE -> R.string.map_severity_severe
    }

private fun Severity.shapeRes(): Int =
    when (this) {
        Severity.MINOR -> R.string.map_shape_circle
        Severity.MODERATE -> R.string.map_shape_triangle
        Severity.SEVERE -> R.string.map_shape_octagon
    }

@Preview(name = "Legend")
@Preview(name = "Legend dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Legend 200%", fontScale = 2f)
@Composable
private fun MapLegendPreview() {
    MapMyRoadTheme {
        MapLegend(expanded = true, onToggle = {})
    }
}

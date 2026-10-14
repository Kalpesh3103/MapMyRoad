package com.mapmyroad.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.ui.format.ScoreBand
import com.mapmyroad.ui.theme.RoadQualityColors

@StringRes
fun ScoreBand.labelRes(): Int =
    when (this) {
        ScoreBand.GOOD -> R.string.band_good
        ScoreBand.FAIR -> R.string.band_fair
        ScoreBand.POOR -> R.string.band_poor
        ScoreBand.NO_DATA -> R.string.band_no_data
    }

fun ScoreBand.color(): Color =
    when (this) {
        ScoreBand.GOOD -> RoadQualityColors.Good
        ScoreBand.FAIR -> RoadQualityColors.Fair
        ScoreBand.POOR -> RoadQualityColors.Poor
        ScoreBand.NO_DATA -> RoadQualityColors.NoData
    }

@StringRes
fun VehicleType.labelRes(): Int =
    when (this) {
        VehicleType.MOTORCYCLE -> R.string.vehicle_motorcycle
        VehicleType.SCOOTER -> R.string.vehicle_scooter
        VehicleType.CAR -> R.string.vehicle_car
        VehicleType.AUTO_RICKSHAW -> R.string.vehicle_auto_rickshaw
    }

fun Severity.color(): Color =
    when (this) {
        Severity.MINOR -> RoadQualityColors.SeverityMinor
        Severity.MODERATE -> RoadQualityColors.SeverityModerate
        Severity.SEVERE -> RoadQualityColors.SeveritySevere
    }

/** Color swatch plus the band label, so the band never depends on color alone (UI_UX_GUIDELINES.md 4.2). */
@Composable
fun BandIndicator(
    band: ScoreBand,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .background(color = band.color(), shape = RoundedCornerShape(4.dp)),
        )
        Text(text = stringResource(band.labelRes()), style = textStyle)
    }
}

/**
 * Hazard severity marker (UI_UX_GUIDELINES.md 4.3): circle for minor, triangle for moderate and an octagon
 * with "!" for severe. Decorative: callers always show the severity label next to it.
 */
@Composable
fun SeverityMarker(
    severity: Severity,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val color = severity.color()
    Canvas(modifier = modifier.size(size)) {
        when (severity) {
            Severity.MINOR -> drawCircle(color = color)
            Severity.MODERATE -> drawPath(path = trianglePath(this.size), color = color)
            Severity.SEVERE -> {
                drawPath(path = octagonPath(this.size), color = color)
                drawExclamationMark(this.size)
            }
        }
    }
}

// Shape proportions below are purely visual, relative to the marker size.
private const val OCTAGON_CORNER = 0.2929f
private const val MARK_LEFT = 0.44f
private const val MARK_TOP = 0.2f
private const val MARK_WIDTH = 0.12f
private const val MARK_HEIGHT = 0.38f
private const val DOT_RADIUS = 0.07f
private const val DOT_CENTER_Y = 0.73f

private fun trianglePath(size: Size): Path =
    Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

private fun octagonPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w * OCTAGON_CORNER, 0f)
        lineTo(w * (1f - OCTAGON_CORNER), 0f)
        lineTo(w, h * OCTAGON_CORNER)
        lineTo(w, h * (1f - OCTAGON_CORNER))
        lineTo(w * (1f - OCTAGON_CORNER), h)
        lineTo(w * OCTAGON_CORNER, h)
        lineTo(0f, h * (1f - OCTAGON_CORNER))
        lineTo(0f, h * OCTAGON_CORNER)
        close()
    }
}

private fun DrawScope.drawExclamationMark(size: Size) {
    val w = size.width
    drawRect(
        color = Color.White,
        topLeft = Offset(w * MARK_LEFT, size.height * MARK_TOP),
        size = Size(w * MARK_WIDTH, size.height * MARK_HEIGHT),
    )
    drawCircle(
        color = Color.White,
        radius = w * DOT_RADIUS,
        center = Offset(w / 2f, size.height * DOT_CENTER_Y),
    )
}

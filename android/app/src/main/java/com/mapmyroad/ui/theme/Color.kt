package com.mapmyroad.ui.theme

import androidx.compose.ui.graphics.Color

// Material 3 color roles, initial values from UI_UX_GUIDELINES.md section 4.1.
internal val PrimaryLight = Color(0xFF1F5FA8)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val SurfaceLight = Color(0xFFFAFAFA)
internal val OnSurfaceLight = Color(0xFF1A1C1E)
internal val ErrorLight = Color(0xFFB3261E)

internal val PrimaryDark = Color(0xFFA9C7FF)
internal val OnPrimaryDark = Color(0xFF00315F)
internal val SurfaceDark = Color(0xFF121316)
internal val OnSurfaceDark = Color(0xFFE3E2E6)
internal val ErrorDark = Color(0xFFF2B8B5)

/**
 * Road quality bands and hazard severity colors (UI_UX_GUIDELINES.md sections 4.2 and 4.3). Initial values,
 * to be checked in sunlight, at night and with a color-blindness simulator. Color is never the only cue:
 * every use must be paired with a line style, marker shape or text label.
 */
object RoadQualityColors {
    val Good = Color(0xFF1B7F79)
    val Fair = Color(0xFFE0A100)
    val Poor = Color(0xFFB3261E)
    val NoData = Color(0xFF9AA0A6).copy(alpha = 0.4f)

    val SeverityMinor = Color(0xFFE0A100)
    val SeverityModerate = Color(0xFFE8710A)
    val SeveritySevere = Color(0xFFB3261E)
}

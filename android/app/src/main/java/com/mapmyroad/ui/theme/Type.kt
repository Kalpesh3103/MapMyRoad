package com.mapmyroad.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale from UI_UX_GUIDELINES.md section 4.4. The system font is used, so Devanagari falls back to the
// device font for Hindi and Marathi. Caption size (12 sp) is never used for essential information.
internal val MapMyRoadTypography =
    Typography(
        displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    )

package com.mapmyroad.ui.format

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.mapmyroad.R
import java.text.DateFormat
import java.util.Date

/**
 * Trip date and start time in the system locale, for example "14 Oct 2026, 09:41". The time follows the system
 * 12 or 24 hour setting (UI_UX_GUIDELINES.md section 9).
 */
fun formatTripDateTime(
    context: Context,
    epochMs: Long,
): String {
    val date = Date(epochMs)
    val locale = context.resources.configuration.locales[0]
    val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date)
    val timeText = AndroidDateFormat.getTimeFormat(context).format(date)
    return context.getString(R.string.common_date_time, dateText, timeText)
}

@Composable
fun rememberTripDateTime(epochMs: Long): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(epochMs, configuration) { formatTripDateTime(context, epochMs) }
}

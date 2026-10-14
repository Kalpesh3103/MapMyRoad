package com.mapmyroad.ui

import androidx.annotation.StringRes
import com.mapmyroad.R

/** The four bottom navigation tabs (UI_UX_GUIDELINES.md section 3). Icons are mapped in the UI layer. */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
) {
    HOME(route = "home", labelRes = R.string.nav_home),
    MAP(route = "map", labelRes = R.string.nav_map),
    TRIPS(route = "trips", labelRes = R.string.nav_trips),
    SETTINGS(route = "settings", labelRes = R.string.nav_settings),
    ;

    companion object {
        val start: TopLevelDestination = HOME
    }
}

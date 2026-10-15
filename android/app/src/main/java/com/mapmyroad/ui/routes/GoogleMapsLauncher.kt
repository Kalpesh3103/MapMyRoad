package com.mapmyroad.ui.routes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mapmyroad.ui.components.openWebPage

/**
 * Opens directions in the Google Maps app (M5-9, PRD RT-4). When the app is missing or disabled, the same Maps URL
 * opens in any browser or app that handles it. The URL is never logged: it holds coordinates.
 */
internal fun Context.openGoogleMapsDirections(url: String) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(GoogleMapsHandoff.GOOGLE_MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        openWebPage(url)
    }
}

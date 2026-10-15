package com.mapmyroad.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mapmyroad.BuildConfig
import com.mapmyroad.ui.components.openWebPage
import timber.log.Timber

/** Play Store links for the update screen. The web link is the fallback when the Play Store app is missing. */
object PlayStoreLinks {
    fun marketUri(packageName: String): String = "market://details?id=$packageName"

    fun webUrl(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"
}

/** Opens this app's Play Store listing, or the web listing when no store app handles `market://`. */
fun Context.openPlayStoreListing(packageName: String = BuildConfig.APPLICATION_ID) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreLinks.marketUri(packageName)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No Play Store app, opening the web listing")
        openWebPage(PlayStoreLinks.webUrl(packageName))
    }
}

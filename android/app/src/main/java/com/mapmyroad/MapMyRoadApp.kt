package com.mapmyroad

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import timber.log.Timber

@HiltAndroidApp
class MapMyRoadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Release builds plant no tree, so nothing is logged (CODING_STANDARDS.md section 4.6, SECURITY.md T22).
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // MapLibre must be initialized once before any MapView is created.
        MapLibre.getInstance(this)
    }
}

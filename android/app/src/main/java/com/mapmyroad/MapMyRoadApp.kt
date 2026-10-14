package com.mapmyroad

import android.app.Application
import com.mapmyroad.sensing.TripRecovery
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MapMyRoadApp : Application() {
    @Inject
    lateinit var tripRecovery: TripRecovery

    override fun onCreate() {
        super.onCreate()
        // Release builds plant no tree, so nothing is logged (CODING_STANDARDS.md section 4.6, SECURITY.md T22).
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // MapLibre must be initialized once before any MapView is created.
        MapLibre.getInstance(this)
        // Closes trips left open by a killed tracking service (UI_UX_GUIDELINES.md section 8). Runs in the background.
        tripRecovery.launchRecovery()
    }
}

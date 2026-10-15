package com.mapmyroad

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapmyroad.data.upload.UploadTrigger
import com.mapmyroad.sensing.TripRecovery
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MapMyRoadApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var tripRecovery: TripRecovery

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var uploadTrigger: UploadTrigger

    // WorkManager initializes on demand from this configuration (the default initializer is removed in the
    // manifest), so workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

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
        // Schedules contribution uploads after trips and on sharing setting changes; does nothing until opted in.
        uploadTrigger.start()
    }
}

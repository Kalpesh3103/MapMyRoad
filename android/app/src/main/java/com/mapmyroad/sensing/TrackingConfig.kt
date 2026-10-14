package com.mapmyroad.sensing

/**
 * Tracking constants (CODING_STANDARDS.md section 3.6: no magic numbers).
 *
 * These are initial values, chosen from ARCHITECTURE.md sections 4.2 and 5 and PRD TS-7.
 * TODO(M2-6): tune the auto-pause and distance values with field drives (TESTING_STRATEGY.md section 4.2).
 */
object TrackingConfig {
    // Sensors (ARCHITECTURE.md section 5 step 1).

    /** 100 Hz. Explicitly not SENSOR_DELAY_FASTEST: stays under the 200 Hz cap that needs HIGH_SAMPLING_RATE_SENSORS. */
    const val SENSOR_SAMPLING_PERIOD_US = 10_000

    /**
     * Hardware batching (ARCHITECTURE.md section 4.2): the sensor hub buffers up to 1 s of samples so the CPU can
     * sleep between deliveries (PRD battery target). Detection is not latency sensitive: events are positioned by
     * their own timestamps, not by delivery time.
     */
    const val SENSOR_MAX_REPORT_LATENCY_US = 1_000_000

    // Location (ARCHITECTURE.md section 4.2: about 1 Hz while driving).

    const val LOCATION_INTERVAL_MS = 1_000L

    // GPS status shown on the live trip screen (UI_UX_GUIDELINES.md S3 and section 8).

    /** Matches the detector's accuracy gate (ARCHITECTURE.md section 5 step 4). */
    const val GPS_GOOD_ACCURACY_M = 20f

    /** No fix for longer than this means "Waiting for GPS". */
    const val GPS_MAX_FIX_AGE_MS = 5_000L

    // Track downsampling (ARCHITECTURE.md section 4.3: about 1 point per second).

    const val TRACK_MIN_INTERVAL_MS = 1_000L

    /** Fixes worse than this are not drawn: they make the trip line zigzag. */
    const val TRACK_MAX_ACCURACY_M = 50f

    // Live distance.

    const val DISTANCE_MAX_ACCURACY_M = 20f

    /** Steps shorter than this are GPS jitter while standing still; the anchor is kept so slow motion still adds up. */
    const val DISTANCE_MIN_STEP_M = 3.0

    /** About 200 km/h. A faster apparent step is a GPS jump and is rejected. */
    const val DISTANCE_MAX_PLAUSIBLE_SPEED_MPS = 55.0

    /** After this many rejected fixes in a row the position is trusted again (re-anchored without adding distance). */
    const val DISTANCE_MAX_CONSECUTIVE_REJECTS = 3

    // Auto-pause and auto-stop (PRD TS-7, PRD flow 7.2).

    /** About 2 km/h: slower than this counts as standing still. */
    const val STATIONARY_SPEED_MPS = 2f / 3.6f

    /** Staying within this radius of the anchor counts as standing still when the speed is unknown. */
    const val STATIONARY_RADIUS_M = 20.0

    /** Stationary this long (with fresh fixes) pauses the trip. */
    const val STATIONARY_PAUSE_AFTER_MS = 120_000L

    /** Stationary this long (counted from the last movement) ends the trip with StopReason.AUTO. */
    const val STATIONARY_STOP_AFTER_MS = 15L * 60L * 1_000L

    /** About 8 km/h on an accurate fix resumes an auto-paused trip. */
    const val MOVING_RESUME_SPEED_MPS = 8f / 3.6f

    const val MOVING_RESUME_ACCURACY_M = 20f

    /** Moving this far from the anchor (accurate fixes) also resumes, for providers that report no speed. */
    const val MOVING_RESUME_RADIUS_M = 50.0

    /** Without a fix this recent, no auto-pause decision is taken (a tunnel is not a stop). */
    const val AUTO_PAUSE_FIX_FRESHNESS_MS = 10_000L

    // Battery (PRD TS-7).

    const val BATTERY_CHECK_INTERVAL_MS = 60_000L

    // Recorder loop and persistence.

    const val TICK_MS = 1_000L

    /** Buffered points, hazards and samples are written in one transaction this often (DATABASE_SCHEMA.md B3). */
    const val PERSIST_INTERVAL_MS = 5_000L

    /** Upper bound for the sensor thread to finish its stop work before the trip is closed anyway. */
    const val STOP_SENSOR_THREAD_TIMEOUT_MS = 3_000L
}

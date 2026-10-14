package com.mapmyroad.detector

/**
 * Every tunable number of the detection pipeline (ARCHITECTURE.md section 5, CODING_STANDARDS.md section 3.6).
 *
 * All values are initial engineering guesses taken from the architecture document or chosen for the synthetic
 * tests. None of them is a measured fact (AGENTS.md, honesty). They are replaced by the calibration of M1-5
 * (TESTING_STRATEGY.md section 3.4); any change must update the regression baseline in the same PR (M2-20).
 *
 * Units are part of every name. Accelerations are in m/s^2 (suffix Mps2), angular rates in rad/s.
 */
data class DetectorConfig(
    // ---- Sampling and filtering (steps 1 to 3) ----
    /**
     * Highest sensor rate the ring buffers are sized for. Above it the rolling baseline covers a shorter time span
     * (oldest samples are dropped), it never allocates. Design assumption: Android 12+ caps normal apps at 200 Hz.
     */
    val maxSampleRateHz: Double = 200.0,
    /**
     * A gap between accelerometer samples longer than this restarts filters and warm-up. Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val maxSensorGapMs: Long = 500L,
    /**
     * No events or roughness samples for this long after the signal chain (re)starts. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val filterWarmupMs: Long = 1_000L,
    /**
     * If no TYPE_GRAVITY sample arrived for longer than this, gravity is estimated from the accelerometer. Initial value,
     * TODO(M1-5): calibrate from field data.
     */
    val gravityStaleMs: Long = 1_000L,
    /**
     * Time constant of the accelerometer low-pass used as the fallback gravity estimate. Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val gravityLowPassTimeConstantMs: Double = 1_000.0,
    /** High-pass corner on vertical acceleration (ARCHITECTURE.md: about 0.5 Hz). Initial value, TODO(M1-5): calibrate from field data. */
    val highPassCutoffHz: Double = 0.5,
    /**
     * Low-pass corner on vertical acceleration (ARCHITECTURE.md: about 20 to 25 Hz). Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val lowPassCutoffHz: Double = 22.0,
    // ---- Gating (step 4) ----
    /** Minimum speed for events and roughness (ARCHITECTURE.md: about 10 km/h). Initial value, TODO(M1-5): calibrate from field data. */
    val minSpeedKmh: Double = 10.0,
    /** The latest fix must be at most this old for the gate to be open. Initial value, TODO(M1-5): calibrate from field data. */
    val maxFixAgeMs: Long = 3_000L,
    /** Maximum horizontal accuracy radius (ARCHITECTURE.md: about 20 m). Initial value, TODO(M1-5): calibrate from field data. */
    val maxAccuracyM: Double = 20.0,
    /** Smoothed gyroscope magnitude above which the phone may be handled. Initial value, TODO(M1-5): calibrate from field data. */
    val handlingGyroThresholdRadPerS: Double = 1.5,
    /** Time constant of the gyroscope magnitude smoothing. Initial value, TODO(M1-5): calibrate from field data. */
    val gyroSmoothingTimeConstantMs: Double = 250.0,
    /**
     * GPS heading change rate below which large rotation means handling, not a turn. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val handlingMaxHeadingRateDegPerS: Double = 20.0,
    /** How long detection stays suppressed after handling was seen. Initial value, TODO(M1-5): calibrate from field data. */
    val handlingHoldMs: Long = 2_000L,
    /**
     * Pending events this close before the handling onset are dropped (smoothing delay). Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val handlingLookbackMs: Long = 500L,
    // ---- Event detection (step 5) ----
    /** Window of the rolling RMS baseline (ARCHITECTURE.md: last few seconds). Initial value, TODO(M1-5): calibrate from field data. */
    val baselineWindowMs: Long = 3_000L,
    /** Adaptive threshold = this multiple of the rolling RMS. Initial value, TODO(M1-5): calibrate from field data. */
    val thresholdRmsMultiplier: Double = 4.0,
    /** Floor of the adaptive threshold on filtered vertical acceleration. Initial value, TODO(M1-5): calibrate from field data. */
    val absoluteMinThresholdMps2: Double = 1.5,
    /**
     * An event ends when the signal stays below this fraction of its start threshold. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val releaseRatio: Double = 0.5,
    /** ... for at least this long. Initial value, TODO(M1-5): calibrate from field data. */
    val releaseHoldMs: Long = 60L,
    /** An event is closed after this long even if the signal stays high. Initial value, TODO(M1-5): calibrate from field data. */
    val maxEventDurationMs: Long = 1_500L,
    /**
     * No new event starts this long after an event ends (ARCHITECTURE.md: a few hundred ms). Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val refractoryMs: Long = 300L,
    // ---- Rear wheel and double impulse (step 6) ----
    /**
     * Wheelbase per vehicle. The rear-wheel hit is expected wheelbase / speed after the front-wheel peak. For every
     * vehicle the detector absorbs that hit into the same event (no double count). Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val wheelbaseM: Map<VehicleType, Double> =
        mapOf(
            VehicleType.MOTORCYCLE to 1.35,
            VehicleType.SCOOTER to 1.25,
            VehicleType.CAR to 2.5,
            VehicleType.AUTO_RICKSHAW to 2.0,
        ),
    /**
     * Vehicles for which the double-impulse pattern (front then rear wheel) is recognized as a feature (ARCHITECTURE.md: four-wheelers).
     */
    val doubleImpulseVehicles: Set<VehicleType> = setOf(VehicleType.CAR, VehicleType.AUTO_RICKSHAW),
    /**
     * Relative tolerance of the rear-wheel timing window around wheelbase / speed. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val doubleImpulseTimingTolerance: Double = 0.3,
    /**
     * Second peak must reach this fraction of the first to count as a double impulse. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val doubleImpulseMinPeakRatio: Double = 0.5,
    /** Upper bound on the expected rear-wheel delay (very low speed). Initial value, TODO(M1-5): calibrate from field data. */
    val maxDoubleImpulseDelayMs: Long = 1_000L,
    /**
     * Multiplier on peakScore when a double impulse is seen. 1.0 means the feature is recorded but does not change the
     * score yet. Initial value, TODO(M1-5): calibrate from field data.
     */
    val doubleImpulsePeakScoreFactor: Double = 1.0,
    // ---- Speed normalization and calibration (steps 7 and 8, M2-5) ----
    /** Speed at which the speed factor is 1. Initial value, TODO(M1-5): calibrate from field data. */
    val referenceSpeedKmh: Double = 30.0,
    /**
     * speedFactor = (speedKmh / referenceSpeedKmh)^speedExponent, clamped. Must be 0 or more (monotonic). Initial value,
     * TODO(M1-5): calibrate from field data.
     */
    val speedExponent: Double = 0.5,
    /** Lower clamp of the speed factor. Initial value, TODO(M1-5): calibrate from field data. */
    val minSpeedFactor: Double = 0.5,
    /** Upper clamp of the speed factor. Initial value, TODO(M1-5): calibrate from field data. */
    val maxSpeedFactor: Double = 2.0,
    /**
     * Per-vehicle multiplier on the peak score (M2-5). Two-wheelers and auto-rickshaws feel larger jolts for the same
     * defect, so their factors are below 1. Initial value, TODO(M1-5): calibrate from field data.
     */
    val vehicleCalibration: Map<VehicleType, Double> =
        mapOf(
            VehicleType.MOTORCYCLE to 0.8,
            VehicleType.SCOOTER to 0.7,
            VehicleType.CAR to 1.0,
            VehicleType.AUTO_RICKSHAW to 0.75,
        ),
    /** Severity bins per vehicle on the normalized peak score. Initial value, TODO(M1-5): calibrate from field data. */
    val severityThresholds: Map<VehicleType, SeverityThresholds> =
        VehicleType.entries.associateWith {
            SeverityThresholds(minorPeakScore = 2.5, moderatePeakScore = 4.5, severePeakScore = 7.0)
        },
    // ---- Speed-breaker heuristic (step 10, on device without map data) ----
    /** The breaker shape is only considered below this speed. Initial value, TODO(M1-5): calibrate from field data. */
    val speedBreakerMaxSpeedKmh: Double = 25.0,
    /** Minimum event duration of a breaker shape. Initial value, TODO(M1-5): calibrate from field data. */
    val speedBreakerMinDurationMs: Long = 300L,
    /**
     * Energy centroid must lie within 0.5 +/- this fraction of the event duration (symmetry). Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val speedBreakerMaxCentroidOffset: Double = 0.2,
    /**
     * Maximum crest factor (peak / RMS over the event); breakers are smooth, potholes impulsive. Initial value,
     * TODO(M1-5): calibrate from field data.
     */
    val speedBreakerMaxCrestFactor: Double = 2.2,
    // ---- Event location (step 9, M2-3) ----
    /** Fixed capacity of the queue of events waiting for the next fix. When full, the oldest is emitted early. */
    val pendingCapacity: Int = 32,
    /** A pending event is emitted after this long even if no newer fix arrived. Initial value, TODO(M1-5): calibrate from field data. */
    val pendingTimeoutMs: Long = 1_500L,
    /**
     * Extrapolate beyond the newest fix by speed and bearing for at most this long. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val maxExtrapolationMs: Long = 1_000L,
    /**
     * Two fixes further apart than this are not interpolated; the nearer fix is used. Initial value, TODO(M1-5): calibrate
     * from field data.
     */
    val maxInterpolationGapMs: Long = 5_000L,
    /** Minimum displacement between two fixes to compute a course from them. Initial value, TODO(M1-5): calibrate from field data. */
    val minCourseDistanceM: Double = 3.0,
    // ---- Roughness windows (step 11, M2-4) ----
    /**
     * Target window length (PRD TS-9: about 50 m). Windows close at the first fix at or beyond it. Initial value,
     * TODO(M1-5): calibrate from field data.
     */
    val windowLengthM: Double = 50.0,
    /** A partial window (flush or gap) is emitted only if at least this long (API_SPEC.md: 20 to 120 m). */
    val minPartialWindowM: Double = 20.0,
    /** Consecutive fixes further apart than this break the window. Initial value, TODO(M1-5): calibrate from field data. */
    val maxWindowFixGapMs: Long = 3_000L,
    /** Segments implying a higher speed are GPS jumps and break the window (API_SPEC.md: 0 to 200 km/h). */
    val maxPlausibleSpeedKmh: Double = 200.0,
    /**
     * A window needs at least this many gated sensor samples, otherwise it is skipped. Initial value, TODO(M1-5):
     * calibrate from field data.
     */
    val minRoughnessSamples: Int = 25,
    /** Speed at which the roughness speed factor is 1. Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessReferenceSpeedKmh: Double = 30.0,
    /** Roughness speed factor exponent (0 or more). Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessSpeedExponent: Double = 0.5,
    /** Lower clamp of the roughness speed factor. Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessMinSpeedFactor: Double = 0.5,
    /** Upper clamp of the roughness speed factor. Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessMaxSpeedFactor: Double = 2.0,
    /** Per-vehicle multiplier on the roughness index (M2-5). Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessCalibration: Map<VehicleType, Double> =
        mapOf(
            VehicleType.MOTORCYCLE to 0.8,
            VehicleType.SCOOTER to 0.7,
            VehicleType.CAR to 1.0,
            VehicleType.AUTO_RICKSHAW to 0.75,
        ),
    // ---- Trip scoring (M2-8) ----
    /** Roughness index at or below which a window scores 100. Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessGood: Double = 0.3,
    /** Roughness index at or above which a window scores 0. Initial value, TODO(M1-5): calibrate from field data. */
    val roughnessPoor: Double = 2.0,
    /** Below this total window length the trip has no smoothness score. Initial value, TODO(M1-5): calibrate from field data. */
    val minScoredDistanceM: Double = 200.0,
) {
    init {
        require(maxSampleRateHz > 0.0) { "maxSampleRateHz must be positive" }
        require(maxSensorGapMs > 0L && filterWarmupMs >= 0L && gravityStaleMs > 0L) { "sensor timing values out of range" }
        require(gravityLowPassTimeConstantMs > 0.0 && gyroSmoothingTimeConstantMs > 0.0) { "time constants must be positive" }
        require(highPassCutoffHz > 0.0 && lowPassCutoffHz > highPassCutoffHz) { "filter corners out of range" }
        require(minSpeedKmh >= 0.0 && maxFixAgeMs > 0L && maxAccuracyM > 0.0) { "gating values out of range" }
        require(handlingGyroThresholdRadPerS > 0.0 && handlingHoldMs >= 0L && handlingLookbackMs >= 0L) { "handling values out of range" }
        require(baselineWindowMs > 0L && thresholdRmsMultiplier > 0.0 && absoluteMinThresholdMps2 > 0.0) { "threshold values out of range" }
        require(releaseRatio > 0.0 && releaseRatio <= 1.0) { "releaseRatio must be in (0, 1]" }
        require(releaseHoldMs >= 0L && maxEventDurationMs > 0L && refractoryMs >= 0L) { "event timing values out of range" }
        require(doubleImpulseTimingTolerance in 0.0..1.0 && maxDoubleImpulseDelayMs > 0L) { "double impulse values out of range" }
        require(doubleImpulsePeakScoreFactor > 0.0) { "doubleImpulsePeakScoreFactor must be positive" }
        require(referenceSpeedKmh > 0.0 && speedExponent >= 0.0) { "speed normalization must be monotonic" }
        require(minSpeedFactor > 0.0 && maxSpeedFactor >= minSpeedFactor) { "speed factor clamps out of range" }
        require(roughnessReferenceSpeedKmh > 0.0 && roughnessSpeedExponent >= 0.0) { "roughness normalization must be monotonic" }
        require(roughnessMinSpeedFactor > 0.0 && roughnessMaxSpeedFactor >= roughnessMinSpeedFactor) { "roughness clamps out of range" }
        require(pendingCapacity > 0 && pendingTimeoutMs >= 0L && maxExtrapolationMs >= 0L) { "pending values out of range" }
        require(windowLengthM > 0.0 && minPartialWindowM in 0.0..windowLengthM) { "window lengths out of range" }
        require(maxWindowFixGapMs > 0L && maxPlausibleSpeedKmh > 0.0 && minRoughnessSamples > 0) { "window values out of range" }
        require(roughnessPoor > roughnessGood && roughnessGood >= 0.0) { "roughnessPoor must be above roughnessGood" }
        require(minScoredDistanceM >= 0.0) { "minScoredDistanceM must not be negative" }
        for (vehicleType in VehicleType.entries) {
            require((wheelbaseM[vehicleType] ?: 0.0) > 0.0) { "missing wheelbase for $vehicleType" }
            require((vehicleCalibration[vehicleType] ?: 0.0) > 0.0) { "missing vehicle calibration for $vehicleType" }
            require((roughnessCalibration[vehicleType] ?: 0.0) > 0.0) { "missing roughness calibration for $vehicleType" }
            require(severityThresholds.containsKey(vehicleType)) { "missing severity thresholds for $vehicleType" }
        }
    }

    companion object {
        /** The initial, uncalibrated configuration. */
        val DEFAULT: DetectorConfig = DetectorConfig()
    }
}

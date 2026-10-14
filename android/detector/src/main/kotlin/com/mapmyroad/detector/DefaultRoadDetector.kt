package com.mapmyroad.detector

import kotlin.math.max

/**
 * Default detection pipeline (ARCHITECTURE.md section 5, tasks M2-1, M2-3, M2-4, M2-5).
 *
 * Threading model: not thread-safe and holds no locks. All methods must be called from one thread (the tracking
 * service's sensor thread); listener callbacks run synchronously on that thread. There is a single writer for all
 * state, so no field is shared across threads.
 *
 * Hot path: [onAccelerometer] and [onGyroscope] run at 50 to 100 Hz and allocate nothing. Every buffer (rolling
 * baseline, pending events) is a primitive array preallocated here, sized from the config (maxSampleRateHz times
 * the baseline window). Fixes (about 1 Hz), emitted events and windows may allocate.
 *
 * Per accelerometer sample:
 * 1. dt from the sensor timestamps. A gap above maxSensorGapMs (or the first sample) restarts filters, baseline and
 *    warm-up. Out-of-order samples are ignored.
 * 2. Gravity from TYPE_GRAVITY, or the accelerometer low-pass fallback when gravity is stale ([GravityEstimator]).
 * 3. a_v = dot(acc, g_hat) - |g| ([VerticalProjection]).
 * 4. First-order high-pass (0.5 Hz) then first-order low-pass (22 Hz), coefficients from the actual dt
 *    ([HighPassFilter], [LowPassFilter]). First order keeps phase distortion and ringing small and needs no
 *    re-design when the rate changes; the roll-off is gentle, which is acceptable for the peak and RMS features.
 * 5. Gate: warmed up, latest fix at most maxFixAgeMs old with speed >= minSpeedKmh and accuracy <= maxAccuracyM,
 *    activity not known to be outside a vehicle, phone not being handled ([HandlingDetector]).
 * 6. Adaptive threshold max(absoluteMinThreshold, k * rolling RMS), computed before the sample enters the baseline
 *    ([RollingRms]), then the event state machine ([EventDetector]).
 * 7. Gated samples feed the roughness segment ([RoughnessAccumulator]).
 *
 * A finalized candidate is scored (peak / speed factor * vehicle calibration, [SpeedNormalization]), binned per
 * vehicle ([SeverityThresholds]), downgraded to minor when it has the speed-breaker shape (step 10 heuristic: below
 * speedBreakerMaxSpeedKmh, long, symmetric energy centroid, low crest factor; OSM-based suppression is done on the
 * server in v2), and put into a fixed-capacity pending queue. Pending events are emitted when a fix newer than the
 * event arrives (so the position is interpolated between the two neighbouring fixes, [PositionInterpolator]), when
 * pendingTimeoutMs passes without such a fix (extrapolated or newest position), when the queue is full (oldest
 * first), or on [flush] (which also finalizes an event still being tracked). Handling onset drops pending events
 * from the last handlingLookbackMs.
 *
 * Deterministic: no randomness, no wall-clock reads; output depends only on the input sequence.
 */
internal class DefaultRoadDetector(
    vehicleType: VehicleType,
    private val listener: DetectorListener,
    private val config: DetectorConfig,
) : RoadDetector {
    private val vehicleCalibration = config.vehicleCalibration.getValue(vehicleType)
    private val severityThresholds = config.severityThresholds.getValue(vehicleType)

    private val maxSensorGapNanos = config.maxSensorGapMs * NANOS_PER_MS
    private val warmupNanos = config.filterWarmupMs * NANOS_PER_MS
    private val maxFixAgeNanos = config.maxFixAgeMs * NANOS_PER_MS
    private val pendingTimeoutNanos = config.pendingTimeoutMs * NANOS_PER_MS
    private val handlingLookbackNanos = config.handlingLookbackMs * NANOS_PER_MS

    private val gravity = GravityEstimator(config.gravityLowPassTimeConstantMs / MS_PER_S, config.gravityStaleMs * NANOS_PER_MS)
    private val highPass = HighPassFilter(config.highPassCutoffHz)
    private val lowPass = LowPassFilter(LowPassFilter.timeConstantForCutoffS(config.lowPassCutoffHz))
    private val baseline =
        RollingRms(
            capacity = (config.maxSampleRateHz * config.baselineWindowMs / MS_PER_S).toInt() + 1,
            windowNanos = config.baselineWindowMs * NANOS_PER_MS,
        )
    private val eventDetector =
        EventDetector(
            config = config,
            wheelbaseM = config.wheelbaseM.getValue(vehicleType),
            isDoubleImpulseVehicle = vehicleType in config.doubleImpulseVehicles,
        )
    private val handling = HandlingDetector(config)
    private val positions = PositionInterpolator(config)
    private val pending = PendingEventQueue(config.pendingCapacity)
    private val roughness = RoughnessAccumulator(config, config.roughnessCalibration.getValue(vehicleType))
    private val eventPosition = EventPosition()

    private var hasAccelerometer = false
    private var lastAccelerometerNanos = 0L
    private var warmupUntilNanos = 0L
    private var isKnownOutOfVehicle = false

    override fun onAccelerometer(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val dtNanos = elapsedNanos - lastAccelerometerNanos
        if (hasAccelerometer && dtNanos <= 0L) return
        val isRestart = !hasAccelerometer || dtNanos > maxSensorGapNanos
        if (isRestart) restartSignalChain(elapsedNanos)
        val dtS = if (isRestart) 0.0 else dtNanos / NANOS_PER_S
        hasAccelerometer = true
        lastAccelerometerNanos = elapsedNanos

        val ax = x.toDouble()
        val ay = y.toDouble()
        val az = z.toDouble()
        gravity.onAccelerometer(elapsedNanos, ax, ay, az, dtS)
        val verticalMps2 = VerticalProjection.verticalAccelMps2(ax, ay, az, gravity.x, gravity.y, gravity.z)
        if (verticalMps2.isNaN()) return
        val filteredMps2 = lowPass.filter(highPass.filter(verticalMps2, dtS), dtS)

        val isGateOpen = elapsedNanos >= warmupUntilNanos && isMotionGateOpen(elapsedNanos) && !handling.isHandling(elapsedNanos)
        val thresholdMps2 = max(config.absoluteMinThresholdMps2, config.thresholdRmsMultiplier * baseline.rms())
        baseline.add(elapsedNanos, filteredMps2)
        val isFinalized =
            eventDetector.step(
                elapsedNanos = elapsedNanos,
                valueMps2 = filteredMps2,
                dtS = dtS,
                thresholdMps2 = thresholdMps2,
                canStart = isGateOpen,
                speedMps = positions.speedMps,
                baseline = baseline,
            )
        if (isFinalized) onCandidate()
        if (isGateOpen) roughness.addSample(filteredMps2)
        if (!pending.isEmpty()) emitReadyPending(elapsedNanos, isForced = false)
    }

    override fun onGyroscope(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val isNewHandling = handling.onGyroscope(elapsedNanos, x.toDouble(), y.toDouble(), z.toDouble())
        if (isNewHandling) {
            eventDetector.abort()
            pending.removeFrom(elapsedNanos - handlingLookbackNanos)
        }
    }

    override fun onGravity(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        gravity.onGravitySensor(elapsedNanos, x.toDouble(), y.toDouble(), z.toDouble())
    }

    override fun onLocation(fix: LocationFix) {
        if (!positions.onFix(fix)) return
        handling.onHeadingRate(positions.headingRateDegPerS)
        val window = roughness.onFix(fix, isFixGood())
        if (window != null) listener.onRoughnessWindow(window)
        if (!pending.isEmpty()) emitReadyPending(fix.elapsedNanos, isForced = false)
    }

    override fun onInVehicle(inVehicle: Boolean?) {
        isKnownOutOfVehicle = inVehicle == false
    }

    override fun flush() {
        if (hasAccelerometer && eventDetector.forceFinalize(lastAccelerometerNanos, baseline)) onCandidate()
        emitReadyPending(nowNanos = 0L, isForced = true)
        val window = roughness.flushPartial()
        if (window != null) listener.onRoughnessWindow(window)
        reset()
    }

    override fun reset() {
        gravity.reset()
        highPass.reset()
        lowPass.reset()
        baseline.clear()
        eventDetector.reset()
        handling.reset()
        positions.reset()
        pending.clear()
        roughness.reset()
        hasAccelerometer = false
        lastAccelerometerNanos = 0L
        warmupUntilNanos = 0L
        isKnownOutOfVehicle = false
    }

    private fun restartSignalChain(elapsedNanos: Long) {
        highPass.reset()
        lowPass.reset()
        gravity.restartLowPass()
        baseline.clear()
        eventDetector.abort()
        warmupUntilNanos = elapsedNanos + warmupNanos
    }

    /** Speed, accuracy, fix age and activity part of the gate (handling and warm-up are checked separately). */
    private fun isMotionGateOpen(elapsedNanos: Long): Boolean =
        positions.newest != null &&
            elapsedNanos - positions.newestNanos <= maxFixAgeNanos &&
            positions.speedMps * KMH_PER_MPS >= config.minSpeedKmh &&
            positions.accuracyM <= config.maxAccuracyM &&
            !isKnownOutOfVehicle

    /** Gate state at a fix for the roughness segment that ends there. */
    private fun isFixGood(): Boolean =
        positions.speedMps * KMH_PER_MPS >= config.minSpeedKmh &&
            positions.accuracyM <= config.maxAccuracyM &&
            !isKnownOutOfVehicle

    private fun onCandidate() {
        val speedKmh = eventDetector.candidateSpeedMps * KMH_PER_MPS
        var peakScore =
            eventDetector.candidatePeakMps2 / SpeedNormalization.eventSpeedFactor(speedKmh, config) * vehicleCalibration
        if (eventDetector.candidateHasDoubleImpulse) peakScore *= config.doubleImpulsePeakScoreFactor
        var severity = severityThresholds.classify(peakScore) ?: return
        if (severity != Severity.MINOR && hasSpeedBreakerShape(speedKmh)) severity = Severity.MINOR
        if (pending.isFull()) emitOldestPending()
        pending.add(eventDetector.candidatePeakNanos, peakScore, severity.level, speedKmh)
    }

    /**
     * Step 10 heuristic, documented as a heuristic: a speed breaker at low speed gives a long, symmetric, smooth
     * rise and fall; a pothole gives a short impulsive hit. Not a measured classifier. TODO(M1-5): validate.
     */
    private fun hasSpeedBreakerShape(speedKmh: Double): Boolean {
        val centroidOffset = eventDetector.candidateCentroidFraction - CENTROID_OF_SYMMETRIC_SHAPE
        return speedKmh < config.speedBreakerMaxSpeedKmh &&
            eventDetector.candidateDurationMs >= config.speedBreakerMinDurationMs &&
            centroidOffset <= config.speedBreakerMaxCentroidOffset &&
            centroidOffset >= -config.speedBreakerMaxCentroidOffset &&
            eventDetector.candidateCrestFactor <= config.speedBreakerMaxCrestFactor
    }

    private fun emitReadyPending(
        nowNanos: Long,
        isForced: Boolean,
    ) {
        while (!pending.isEmpty()) {
            val eventNanos = pending.oldestElapsedNanos()
            val hasNewerFix = positions.newest != null && positions.newestNanos >= eventNanos
            val isTimedOut = nowNanos - eventNanos >= pendingTimeoutNanos
            if (!isForced && !hasNewerFix && !isTimedOut) return
            emitOldestPending()
        }
    }

    private fun emitOldestPending() {
        val eventNanos = pending.oldestElapsedNanos()
        val severity = Severity.fromLevel(pending.oldestSeverityLevel())
        if (severity != null && positions.locate(eventNanos, eventPosition)) {
            listener.onBump(
                BumpEvent(
                    elapsedNanos = eventNanos,
                    wallTimeMs = eventPosition.wallTimeMs,
                    lat = eventPosition.lat,
                    lon = eventPosition.lon,
                    severity = severity,
                    speedKmh = pending.oldestSpeedKmh(),
                    peakScore = pending.oldestPeakScore(),
                    headingDeg = if (eventPosition.headingDeg == EventPosition.UNKNOWN_HEADING) null else eventPosition.headingDeg,
                ),
            )
        }
        pending.removeOldest()
    }

    private companion object {
        const val CENTROID_OF_SYMMETRIC_SHAPE = 0.5
    }
}

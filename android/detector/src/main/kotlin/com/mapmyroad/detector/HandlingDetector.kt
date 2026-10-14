package com.mapmyroad.detector

import kotlin.math.sqrt

/**
 * Phone-handling gate (ARCHITECTURE.md section 5 step 4, PRD TS-5): large rotation without a matching GPS heading
 * change means the phone is being picked up or moved, so detection is suppressed for a hold time.
 *
 * The gyroscope magnitude is smoothed with a time-constant low-pass (coefficient from the measured interval) so
 * that the short pitch spike of a pothole hit does not look like handling. The heading change rate comes from the
 * fixes; when it is unknown it counts as small.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class HandlingDetector(
    private val config: DetectorConfig,
) {
    private val gyroLowPass = LowPassFilter(config.gyroSmoothingTimeConstantMs / MS_PER_S)
    private val maxGapNanos = config.maxSensorGapMs * NANOS_PER_MS
    private val holdNanos = config.handlingHoldMs * NANOS_PER_MS
    private var lastGyroNanos = 0L
    private var hasGyro = false
    private var headingRateDegPerS = 0.0
    private var handlingUntilNanos = 0L
    private var hasHandled = false

    /** Smoothed gyroscope magnitude, rad/s. */
    var smoothedGyroRadPerS = 0.0
        private set

    /**
     * Feeds one gyroscope sample. Returns true when this sample starts a new handling period (the caller then
     * discards recent pending events), false otherwise, including while a period is being extended.
     */
    fun onGyroscope(
        elapsedNanos: Long,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean {
        val dtNanos = elapsedNanos - lastGyroNanos
        if (hasGyro && dtNanos <= 0L) return false
        if (!hasGyro || dtNanos > maxGapNanos) gyroLowPass.reset()
        val dtS = if (hasGyro) dtNanos / NANOS_PER_S else 0.0
        hasGyro = true
        lastGyroNanos = elapsedNanos
        smoothedGyroRadPerS = gyroLowPass.filter(sqrt(x * x + y * y + z * z), dtS)
        val isRotatingWithoutTurn =
            smoothedGyroRadPerS > config.handlingGyroThresholdRadPerS &&
                headingRateDegPerS < config.handlingMaxHeadingRateDegPerS
        if (!isRotatingWithoutTurn) return false
        val wasHandling = isHandling(elapsedNanos)
        handlingUntilNanos = elapsedNanos + holdNanos
        hasHandled = true
        return !wasHandling
    }

    /** Latest GPS heading change rate, deg/s, 0 when unknown. */
    fun onHeadingRate(rateDegPerS: Double) {
        headingRateDegPerS = if (rateDegPerS.isNaN()) 0.0 else rateDegPerS
    }

    fun isHandling(elapsedNanos: Long): Boolean = hasHandled && elapsedNanos < handlingUntilNanos

    fun reset() {
        gyroLowPass.reset()
        lastGyroNanos = 0L
        hasGyro = false
        headingRateDegPerS = 0.0
        handlingUntilNanos = 0L
        hasHandled = false
        smoothedGyroRadPerS = 0.0
    }
}

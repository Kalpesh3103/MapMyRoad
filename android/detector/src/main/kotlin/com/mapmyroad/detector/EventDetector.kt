package com.mapmyroad.detector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Candidate detection and feature extraction (ARCHITECTURE.md section 5 steps 5 and 6).
 *
 * State machine, fed one filtered vertical acceleration sample at a time:
 * - IDLE: an event starts when starting is allowed (gate open, out of the refractory period) and |a| exceeds the
 *   adaptive threshold. The threshold is frozen at the start so the event's own energy does not move it.
 * - ACTIVE: tracks peak |a|, energy (sum of a^2 dt), the energy time-centroid and the last time |a| was above
 *   releaseRatio * threshold. The event ends after releaseHoldMs below that level, or after maxEventDurationMs.
 * - WATCH: waits for the rear-wheel window, wheelbase / speed after the peak (+/- tolerance, capped at
 *   maxDoubleImpulseDelayMs). Nothing new can start until the window closes and the refractory period after the
 *   event end has passed, so the rear-wheel hit is never counted as a second event. When the window has closed the
 *   candidate is final: the largest |a| inside the window (read back from the baseline ring buffer) is the second
 *   peak, and for double-impulse vehicles a second peak of at least doubleImpulseMinPeakRatio * peak marks a double
 *   impulse.
 *
 * [step] returns true on the sample that finalizes a candidate; its features are then readable from the
 * `candidate*` properties until the next call.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class EventDetector(
    config: DetectorConfig,
    private val wheelbaseM: Double,
    private val isDoubleImpulseVehicle: Boolean,
) {
    private val releaseRatio = config.releaseRatio
    private val releaseHoldNanos = config.releaseHoldMs * NANOS_PER_MS
    private val maxDurationNanos = config.maxEventDurationMs * NANOS_PER_MS
    private val refractoryNanos = config.refractoryMs * NANOS_PER_MS
    private val maxDelayNanos = config.maxDoubleImpulseDelayMs * NANOS_PER_MS
    private val timingTolerance = config.doubleImpulseTimingTolerance
    private val doubleImpulseMinPeakRatio = config.doubleImpulseMinPeakRatio

    private var state = STATE_IDLE
    private var refractoryUntilNanos = 0L
    private var hasRefractory = false

    private var startNanos = 0L
    private var releaseLevel = 0.0
    private var lastAboveNanos = 0L
    private var peak = 0.0
    private var peakNanos = 0L
    private var energy = 0.0
    private var energyTimeSum = 0.0
    private var speedMpsAtStart = 0.0
    private var endNanos = 0L
    private var watchFromNanos = 0L
    private var watchUntilNanos = 0L
    private var finalizeAtNanos = 0L

    /** Peak |filtered vertical acceleration|, m/s^2. */
    var candidatePeakMps2 = 0.0
        private set

    /** Time of the peak, used as the event time. */
    var candidatePeakNanos = 0L
        private set

    /** Sum of a^2 dt over the event, (m/s^2)^2 s. */
    var candidateEnergy = 0.0
        private set

    /** From the first threshold crossing to the last sample above the release level. */
    var candidateDurationMs = 0.0
        private set

    /** Energy time-centroid as a fraction of the duration, 0.5 for a symmetric shape. */
    var candidateCentroidFraction = 0.5
        private set

    /** Peak / RMS over the event duration; about 1.4 for a smooth hump, larger for impulsive hits. */
    var candidateCrestFactor = 0.0
        private set

    /** Speed from the gate when the event started, m/s. */
    var candidateSpeedMps = 0.0
        private set

    /** Largest |a| in the rear-wheel window, m/s^2. */
    var candidateSecondPeakMps2 = 0.0
        private set

    /** True for double-impulse vehicles when the rear-wheel peak was strong enough. */
    var candidateHasDoubleImpulse = false
        private set

    /** True while an event is being tracked or watched (not idle). */
    fun isBusy(): Boolean = state != STATE_IDLE

    /**
     * Feeds one sample. [canStart] is the gate state, [thresholdMps2] the adaptive threshold, [baseline] the ring
     * buffer that already contains this sample (used to find the rear-wheel peak). Returns true when a candidate
     * was finalized on this sample.
     */
    fun step(
        elapsedNanos: Long,
        valueMps2: Double,
        dtS: Double,
        thresholdMps2: Double,
        canStart: Boolean,
        speedMps: Double,
        baseline: RollingRms,
    ): Boolean {
        val magnitude = abs(valueMps2)
        when (state) {
            STATE_IDLE -> {
                val isRefractory = hasRefractory && elapsedNanos < refractoryUntilNanos
                if (canStart && !isRefractory && magnitude > thresholdMps2) start(elapsedNanos, magnitude, dtS, thresholdMps2, speedMps)
            }
            STATE_ACTIVE -> {
                val square = valueMps2 * valueMps2
                energy += square * dtS
                energyTimeSum += square * dtS * (elapsedNanos - startNanos).toDouble()
                if (magnitude > peak) {
                    peak = magnitude
                    peakNanos = elapsedNanos
                }
                if (magnitude >= releaseLevel) lastAboveNanos = elapsedNanos
                val isReleased = elapsedNanos - lastAboveNanos >= releaseHoldNanos
                val isTooLong = elapsedNanos - startNanos >= maxDurationNanos
                if (isReleased || isTooLong) endEvent(elapsedNanos, isTooLong)
            }
            else -> {
                if (elapsedNanos >= finalizeAtNanos) {
                    finalizeCandidate(baseline)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Ends tracking at [elapsedNanos] without waiting for release or the rear-wheel window (used by flush).
     * Returns true when a candidate was finalized; its features are then readable as after [step].
     */
    fun forceFinalize(
        elapsedNanos: Long,
        baseline: RollingRms,
    ): Boolean {
        if (state == STATE_IDLE) return false
        if (state == STATE_ACTIVE) endEvent(elapsedNanos, isTooLong = false)
        finalizeCandidate(baseline)
        return true
    }

    /** Drops the event being tracked, if any (handling detected, signal chain restarted). Keeps the refractory time. */
    fun abort() {
        state = STATE_IDLE
    }

    fun reset() {
        state = STATE_IDLE
        refractoryUntilNanos = 0L
        hasRefractory = false
        candidatePeakMps2 = 0.0
        candidatePeakNanos = 0L
        candidateEnergy = 0.0
        candidateDurationMs = 0.0
        candidateCentroidFraction = 0.5
        candidateCrestFactor = 0.0
        candidateSpeedMps = 0.0
        candidateSecondPeakMps2 = 0.0
        candidateHasDoubleImpulse = false
    }

    private fun start(
        elapsedNanos: Long,
        magnitude: Double,
        dtS: Double,
        thresholdMps2: Double,
        speedMps: Double,
    ) {
        state = STATE_ACTIVE
        startNanos = elapsedNanos
        releaseLevel = thresholdMps2 * releaseRatio
        lastAboveNanos = elapsedNanos
        peak = magnitude
        peakNanos = elapsedNanos
        energy = magnitude * magnitude * dtS
        energyTimeSum = 0.0
        speedMpsAtStart = speedMps
    }

    private fun endEvent(
        elapsedNanos: Long,
        isTooLong: Boolean,
    ) {
        endNanos = if (isTooLong) elapsedNanos else lastAboveNanos
        val delayNanos =
            if (speedMpsAtStart > 0.0) {
                min(maxDelayNanos.toDouble(), wheelbaseM / speedMpsAtStart * NANOS_PER_S).toLong()
            } else {
                maxDelayNanos
            }
        watchFromNanos = peakNanos + (delayNanos * (1.0 - timingTolerance)).toLong()
        watchUntilNanos = peakNanos + (delayNanos * (1.0 + timingTolerance)).toLong()
        finalizeAtNanos = max(elapsedNanos, watchUntilNanos)
        refractoryUntilNanos = max(endNanos + refractoryNanos, finalizeAtNanos)
        hasRefractory = true
        state = STATE_WATCH
    }

    private fun finalizeCandidate(baseline: RollingRms) {
        state = STATE_IDLE
        val durationNanos = max(endNanos - startNanos, 1L)
        candidatePeakMps2 = peak
        candidatePeakNanos = peakNanos
        candidateEnergy = energy
        candidateDurationMs = durationNanos / NANOS_PER_MS.toDouble()
        candidateCentroidFraction = if (energy > 0.0) energyTimeSum / energy / durationNanos.toDouble() else 0.5
        val meanSquare = energy / (durationNanos / NANOS_PER_S)
        candidateCrestFactor = if (meanSquare > 0.0) peak / sqrt(meanSquare) else 0.0
        candidateSpeedMps = speedMpsAtStart
        candidateSecondPeakMps2 = baseline.maxAbsBetween(watchFromNanos, watchUntilNanos)
        candidateHasDoubleImpulse = isDoubleImpulseVehicle && candidateSecondPeakMps2 >= doubleImpulseMinPeakRatio * peak
    }

    private companion object {
        const val STATE_IDLE = 0
        const val STATE_ACTIVE = 1
        const val STATE_WATCH = 2
    }
}

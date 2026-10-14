package com.mapmyroad.detector

import kotlin.math.PI

/**
 * First-order IIR low-pass (exponential smoothing) whose coefficient is computed from the actual sample interval on
 * every call, so the corner frequency holds when the device delivers 50, 100 or a jittery rate.
 *
 * alpha = dt / (tau + dt), output += alpha * (input - output). The first sample initializes the output.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class LowPassFilter(
    private val timeConstantS: Double,
) {
    private var output = 0.0
    private var isInitialized = false

    fun filter(
        input: Double,
        dtS: Double,
    ): Double {
        if (!isInitialized) {
            output = input
            isInitialized = true
            return output
        }
        val alpha = dtS / (timeConstantS + dtS)
        output += alpha * (input - output)
        return output
    }

    /** Last output, 0 before the first sample. */
    fun value(): Double = output

    fun isInitialized(): Boolean = isInitialized

    fun reset() {
        output = 0.0
        isInitialized = false
    }

    companion object {
        /** tau = 1 / (2 pi fc) for a first-order section. */
        fun timeConstantForCutoffS(cutoffHz: Double): Double = 1.0 / (2.0 * PI * cutoffHz)
    }
}

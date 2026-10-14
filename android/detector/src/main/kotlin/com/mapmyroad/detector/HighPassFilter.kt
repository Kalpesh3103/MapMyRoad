package com.mapmyroad.detector

/**
 * First-order IIR high-pass with the coefficient computed from the actual sample interval on every call.
 *
 * y[n] = a * (y[n-1] + x[n] - x[n-1]), a = tau / (tau + dt), tau = 1 / (2 pi fc). The first sample primes the state
 * and returns 0, so switching the filter on does not produce a step.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class HighPassFilter(
    cutoffHz: Double,
) {
    private val timeConstantS = LowPassFilter.timeConstantForCutoffS(cutoffHz)
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var isInitialized = false

    fun filter(
        input: Double,
        dtS: Double,
    ): Double {
        if (!isInitialized) {
            previousInput = input
            previousOutput = 0.0
            isInitialized = true
            return 0.0
        }
        val alpha = timeConstantS / (timeConstantS + dtS)
        val output = alpha * (previousOutput + input - previousInput)
        previousInput = input
        previousOutput = output
        return output
    }

    fun reset() {
        previousInput = 0.0
        previousOutput = 0.0
        isInitialized = false
    }
}

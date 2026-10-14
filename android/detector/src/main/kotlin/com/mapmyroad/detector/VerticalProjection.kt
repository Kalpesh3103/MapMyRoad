package com.mapmyroad.detector

import kotlin.math.sqrt

/**
 * Orientation normalization (ARCHITECTURE.md section 5 step 2): projects the raw accelerometer onto the gravity
 * direction and removes gravity, so the result does not depend on how the phone is mounted, pocketed or bagged.
 */
internal object VerticalProjection {
    /** Gravity vectors shorter than this are not trustworthy (sensor not settled); a validity guard, not a tuning value. */
    const val MIN_GRAVITY_NORM_MPS2 = 1.0

    /**
     * a_v = dot(acc, g_hat) - |g|. Positive is upward (away from the ground) acceleration. Returns NaN when the
     * gravity vector is shorter than [MIN_GRAVITY_NORM_MPS2].
     */
    fun verticalAccelMps2(
        ax: Double,
        ay: Double,
        az: Double,
        gx: Double,
        gy: Double,
        gz: Double,
    ): Double {
        val gravityNorm = sqrt(gx * gx + gy * gy + gz * gz)
        if (gravityNorm < MIN_GRAVITY_NORM_MPS2) return Double.NaN
        return (ax * gx + ay * gy + az * gz) / gravityNorm - gravityNorm
    }
}

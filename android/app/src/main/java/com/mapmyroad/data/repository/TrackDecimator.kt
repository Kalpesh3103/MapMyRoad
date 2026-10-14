package com.mapmyroad.data.repository

/** Stride decimation for display tracks. Pure Kotlin, JVM-tested. */
object TrackDecimator {
    /** Points per trip sent to the personal map. A polyline with more points than this looks the same at map zooms. */
    const val MAX_POINTS_PER_TRIP = 2000

    /**
     * Returns at most [maxPoints] items of [points] by keeping every n-th item, always including the first and the
     * last. Lists that already fit are returned unchanged.
     */
    fun <T> decimate(
        points: List<T>,
        maxPoints: Int = MAX_POINTS_PER_TRIP,
    ): List<T> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (points.size <= maxPoints) return points
        // Keep maxPoints - 1 evenly strided items from the body, then the last item.
        val stride = (points.size - 1).toDouble() / (maxPoints - 1)
        val result = ArrayList<T>(maxPoints)
        for (i in 0 until maxPoints - 1) {
            result.add(points[(i * stride).toInt()])
        }
        result.add(points.last())
        return result
    }
}

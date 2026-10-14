package com.mapmyroad.detector

/** Mutable output holder for [PositionInterpolator.locate], reused to avoid allocation. */
internal class EventPosition {
    var lat = 0.0
    var lon = 0.0
    var wallTimeMs = 0L

    /** 0 to 359, or [UNKNOWN_HEADING]. */
    var headingDeg = UNKNOWN_HEADING

    companion object {
        const val UNKNOWN_HEADING = -1
    }
}

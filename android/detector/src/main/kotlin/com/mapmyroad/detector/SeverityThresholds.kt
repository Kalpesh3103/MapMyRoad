package com.mapmyroad.detector

/**
 * Severity bins on the normalized peak score (ARCHITECTURE.md section 5 step 8, PRD TS-4).
 *
 * A score below [minorPeakScore] is not an event. The values are initial values, not measured facts.
 * TODO(M1-5): calibrate from field data.
 */
data class SeverityThresholds(
    val minorPeakScore: Double,
    val moderatePeakScore: Double,
    val severePeakScore: Double,
) {
    init {
        require(minorPeakScore > 0.0) { "minorPeakScore must be positive" }
        require(moderatePeakScore > minorPeakScore) { "moderatePeakScore must be above minorPeakScore" }
        require(severePeakScore > moderatePeakScore) { "severePeakScore must be above moderatePeakScore" }
    }

    /** Returns the severity for [peakScore], or null when it is below the minor threshold (or NaN). */
    fun classify(peakScore: Double): Severity? =
        when {
            peakScore >= severePeakScore -> Severity.SEVERE
            peakScore >= moderatePeakScore -> Severity.MODERATE
            peakScore >= minorPeakScore -> Severity.MINOR
            else -> null
        }
}

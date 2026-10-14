package com.mapmyroad.detector

import kotlin.math.abs

/** A detector output reduced to what the metrics need; [tMs] is relative to the trace start. */
internal data class Detection(
    val tMs: Double,
    val severityLevel: Int,
)

/** Labels and detections of one trace. */
internal data class LabeledRun(
    val name: String,
    val vehicleType: VehicleType,
    val labels: List<TraceLabel>,
    val detections: List<Detection>,
)

/**
 * Detector quality metrics of TESTING_STRATEGY.md section 3.2 (M2-20). Every metric is "higher is better" so the
 * regression gate can compare all of them the same way; the speed-breaker false-alarm rate is reported as its
 * complement [speedBreakerCleanPassRate]. A metric without a denominator is NaN.
 *
 * Matching: a detection matches a label when its time is within [matchWindowMs] of the label (of the label interval
 * for rough patches). The 15 m position condition of section 3.2 is not applied: synthetic labels and the fixture
 * labels format carry no position.
 */
internal data class DetectorMetrics(
    /** Severe pothole labels matched by a severe detection / severe pothole labels. */
    val recallSevere: Double,
    /** Severe detections matching a severe pothole label / severe detections. */
    val precisionSevere: Double,
    /** Matched pothole labels whose nearest detection is within one severity band / matched pothole labels with severity. */
    val severityAgreement: Double,
    /** Speed-breaker passes without a severe detection / speed-breaker passes (1 minus the false-alarm rate). */
    val speedBreakerCleanPassRate: Double,
    /** Pothole labels matched by any detection / pothole labels. */
    val recallAny: Double,
    /** Detections matching any label (pothole, speed breaker, rough patch) / detections. */
    val precisionAny: Double,
    val severeLabelCount: Int,
    val severeDetectionCount: Int,
    val detectionCount: Int,
) {
    fun toMap(): Map<String, Double> =
        linkedMapOf(
            "recallSevere" to recallSevere,
            "precisionSevere" to precisionSevere,
            "severityAgreement" to severityAgreement,
            "speedBreakerCleanPassRate" to speedBreakerCleanPassRate,
            "recallAny" to recallAny,
            "precisionAny" to precisionAny,
        )

    companion object {
        const val DEFAULT_MATCH_WINDOW_MS = 1_500.0
        private const val SEVERE = 3

        fun compute(
            runs: List<LabeledRun>,
            matchWindowMs: Double = DEFAULT_MATCH_WINDOW_MS,
        ): DetectorMetrics {
            var severeLabels = 0
            var severeLabelsMatched = 0
            var severeDetections = 0
            var severeDetectionsMatched = 0
            var agreementTotal = 0
            var agreementOk = 0
            var breakerPasses = 0
            var breakerCleanPasses = 0
            var potholeLabels = 0
            var potholeLabelsMatched = 0
            var detections = 0
            var detectionsMatched = 0
            for (run in runs) {
                for (label in run.labels) {
                    val matching = run.detections.filter { matches(it, label, matchWindowMs) }
                    when (label.type) {
                        TraceLabel.POTHOLE -> {
                            potholeLabels++
                            if (matching.isNotEmpty()) potholeLabelsMatched++
                            if (label.severity == SEVERE) {
                                severeLabels++
                                if (matching.any { it.severityLevel == SEVERE }) severeLabelsMatched++
                            }
                            val labelSeverity = label.severity
                            if (labelSeverity != null && matching.isNotEmpty()) {
                                agreementTotal++
                                val nearest = matching.minBy { abs(it.tMs - label.tMs) }
                                if (abs(nearest.severityLevel - labelSeverity) <= 1) agreementOk++
                            }
                        }
                        TraceLabel.SPEED_BREAKER -> {
                            breakerPasses++
                            if (matching.none { it.severityLevel == SEVERE }) breakerCleanPasses++
                        }
                    }
                }
                for (detection in run.detections) {
                    detections++
                    if (run.labels.any { matches(detection, it, matchWindowMs) }) detectionsMatched++
                    if (detection.severityLevel == SEVERE) {
                        severeDetections++
                        val isTrue =
                            run.labels.any {
                                it.type == TraceLabel.POTHOLE && it.severity == SEVERE && matches(detection, it, matchWindowMs)
                            }
                        if (isTrue) severeDetectionsMatched++
                    }
                }
            }
            return DetectorMetrics(
                recallSevere = ratio(severeLabelsMatched, severeLabels),
                precisionSevere = ratio(severeDetectionsMatched, severeDetections),
                severityAgreement = ratio(agreementOk, agreementTotal),
                speedBreakerCleanPassRate = ratio(breakerCleanPasses, breakerPasses),
                recallAny = ratio(potholeLabelsMatched, potholeLabels),
                precisionAny = ratio(detectionsMatched, detections),
                severeLabelCount = severeLabels,
                severeDetectionCount = severeDetections,
                detectionCount = detections,
            )
        }

        private fun matches(
            detection: Detection,
            label: TraceLabel,
            matchWindowMs: Double,
        ): Boolean {
            val startMs = label.tMs.toDouble()
            val endMs = startMs + (label.durationMs ?: 0L).toDouble()
            return detection.tMs >= startMs - matchWindowMs && detection.tMs <= endMs + matchWindowMs
        }

        private fun ratio(
            numerator: Int,
            denominator: Int,
        ): Double = if (denominator == 0) Double.NaN else numerator.toDouble() / denominator.toDouble()
    }
}

package com.mapmyroad.detector

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Properties

/**
 * Detector metric regression gate (M2-20, TESTING_STRATEGY.md section 3.2). Runs the fixed synthetic scenarios (and
 * any recorded fixtures in src/test/resources/traces/) and fails when a metric drops more than [TOLERANCE] below the
 * checked-in baseline in src/test/resources/detector-baseline.properties. Improving a metric means raising the
 * baseline in the same PR with an explanation.
 */
class DetectorRegressionTest {
    @Test
    fun syntheticScenarios_meetBaseline() {
        val baseline = loadBaseline()
        val runs = SyntheticScenarios.all().map { SyntheticScenarios.run(it) }
        val measured = linkedMapOf<String, Double>()
        DetectorMetrics.compute(runs).toMap().forEach { (metric, value) -> measured["synthetic.all.$metric"] = value }
        for (vehicleType in VehicleType.entries) {
            val metrics = DetectorMetrics.compute(runs.filter { it.vehicleType == vehicleType })
            metrics.toMap().forEach { (metric, value) -> measured["synthetic.${vehicleType.code}.$metric"] = value }
        }
        assertMeetsBaseline("synthetic.", measured, baseline)
    }

    @Test
    fun recordedFixtures_meetBaseline() {
        val fixtures = FixtureTraces.load()
        if (fixtures.isEmpty()) return
        val baseline = loadBaseline()
        val runs = fixtures.map { FixtureTraces.run(it) }
        val measured = linkedMapOf<String, Double>()
        DetectorMetrics.compute(runs).toMap().forEach { (metric, value) -> measured["fixtures.all.$metric"] = value }
        assertTrue(
            "recorded fixtures are present but the baseline has no fixtures.* keys; add them (measured: $measured)",
            baseline.stringPropertyNames().any { it.startsWith("fixtures.") },
        )
        assertMeetsBaseline("fixtures.", measured, baseline)
    }

    private fun assertMeetsBaseline(
        prefix: String,
        measured: Map<String, Double>,
        baseline: Properties,
    ) {
        val failures = mutableListOf<String>()
        val keys = baseline.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()
        assertTrue("baseline has no $prefix keys", keys.isNotEmpty())
        for (key in keys) {
            val expected = baseline.getProperty(key).trim().toDouble()
            val actual = measured[key]
            when {
                actual == null -> failures += "$key: no such metric"
                actual.isNaN() -> failures += "$key: no data (NaN), baseline $expected"
                actual < expected - TOLERANCE -> failures += "$key: %.3f is below baseline %.3f - %.2f".format(actual, expected, TOLERANCE)
            }
        }
        if (failures.isNotEmpty()) fail("Detector metrics regressed:\n" + failures.joinToString("\n") + "\nmeasured: $measured")
    }

    private fun loadBaseline(): Properties {
        val stream =
            javaClass.classLoader.getResourceAsStream(BASELINE_RESOURCE)
                ?: throw AssertionError("missing $BASELINE_RESOURCE")
        return stream.use { Properties().apply { load(it) } }
    }

    private companion object {
        const val BASELINE_RESOURCE = "detector-baseline.properties"
        const val TOLERANCE = 0.02
    }
}

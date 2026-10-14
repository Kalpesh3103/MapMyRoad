package com.mapmyroad.detector

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** Shapes of injected vertical acceleration events in the world frame. */
internal enum class BumpShape {
    /** Pothole-like hit: a half-sine of the given duration and amplitude, then a smaller opposite rebound. */
    IMPACT,

    /** Speed-breaker-like hump: a full cosine (+, -, +) over the given duration, symmetric about its center. */
    HUMP,
}

/** A labeled event as in TESTING_STRATEGY.md section 3.1 (tMs relative to the trace start). */
internal data class TraceLabel(
    val tMs: Long,
    val type: String,
    val severity: Int? = null,
    val durationMs: Long? = null,
) {
    companion object {
        const val POTHOLE = "pothole"
        const val SPEED_BREAKER = "speed_breaker"
        const val ROUGH_PATCH = "rough_patch"
    }
}

/** One injected event, centered at [tS] seconds after the trace start. The rear wheel repeats it wheelbase/speed later. */
internal data class SyntheticBump(
    val tS: Double,
    val shape: BumpShape,
    val amplitudeMps2: Double,
    val durationMs: Double,
    val label: TraceLabel?,
    val rearWheelFactor: Double = DEFAULT_REAR_WHEEL_FACTOR,
) {
    companion object {
        const val DEFAULT_REAR_WHEEL_FACTOR = 0.7
        const val REBOUND_FACTOR = 0.3
        const val POTHOLE_DURATION_MS = 80.0
        const val BREAKER_LENGTH_M = 3.5

        /**
         * A pothole whose raw peak gives [targetPeakScore] for [vehicleType] at [speedKmh] under [config], i.e. the raw
         * jolt grows with speed exactly as the speed normalization models it.
         */
        fun pothole(
            tS: Double,
            targetPeakScore: Double,
            speedKmh: Double,
            vehicleType: VehicleType,
            severity: Int?,
            config: DetectorConfig = DetectorConfig.DEFAULT,
            rearWheelFactor: Double = DEFAULT_REAR_WHEEL_FACTOR,
        ): SyntheticBump {
            val amplitude =
                targetPeakScore * SpeedNormalization.eventSpeedFactor(speedKmh, config) /
                    config.vehicleCalibration.getValue(vehicleType)
            return SyntheticBump(
                tS = tS,
                shape = BumpShape.IMPACT,
                amplitudeMps2 = amplitude,
                durationMs = POTHOLE_DURATION_MS,
                label = TraceLabel((tS * 1000.0).roundToLong(), TraceLabel.POTHOLE, severity),
                rearWheelFactor = rearWheelFactor,
            )
        }

        /**
         * A sin^2 hump of [heightM] and [lengthM] crossed at [speedKmh]: vertical acceleration 2 h (pi v / L)^2 cos(2 pi x / L),
         * lasting L / v.
         */
        fun speedBreaker(
            tS: Double,
            heightM: Double,
            speedKmh: Double,
            lengthM: Double = BREAKER_LENGTH_M,
        ): SyntheticBump {
            val speedMps = speedKmh / KMH_PER_MPS
            val omega = PI * speedMps / lengthM
            return SyntheticBump(
                tS = tS,
                shape = BumpShape.HUMP,
                amplitudeMps2 = 2.0 * heightM * omega * omega,
                durationMs = lengthM / speedMps * 1000.0,
                label = TraceLabel((tS * 1000.0).roundToLong(), TraceLabel.SPEED_BREAKER),
            )
        }
    }
}

/** A stretch of rough road: vertical noise multiplied by [noiseMultiplier]. */
internal data class RoughPatch(
    val startS: Double,
    val durationS: Double,
    val noiseMultiplier: Double,
    val isLabeled: Boolean = true,
)

internal data class TimeInterval(
    val startS: Double,
    val endS: Double,
) {
    fun contains(tS: Double): Boolean = tS >= startS && tS < endS
}

/** Everything a detector emitted for one trace. */
internal data class TraceOutput(
    val events: List<BumpEvent>,
    val windows: List<RoughnessWindow>,
    val fixes: List<LocationFix>,
    val baseElapsedNanos: Long,
) {
    fun eventTimeMs(event: BumpEvent): Double = (event.elapsedNanos - baseElapsedNanos) / 1.0e6
}

/** Collects listener output in order. */
internal class CollectingListener : DetectorListener {
    val events = mutableListOf<BumpEvent>()
    val windows = mutableListOf<RoughnessWindow>()

    override fun onBump(event: BumpEvent) {
        events += event
    }

    override fun onRoughnessWindow(window: RoughnessWindow) {
        windows += window
    }
}

/**
 * Test-only synthetic drive (TESTING_STRATEGY.md section 3.3): sensor samples at [sampleRateHz] for a phone in an
 * arbitrary orientation, with gravity, seeded road noise, injected bumps, a speed profile and 1 Hz GPS fixes along a
 * straight line from a start point. Deterministic for a given seed.
 *
 * World frame is east, north, up. The device orientation is R = Rz(yaw) Ry(pitch) Rx(roll) (device to world); the
 * accelerometer reports R^T (a_world + g up), the gravity sensor R^T g up.
 */
internal class SyntheticTrace(
    val durationS: Double,
    val speedKmhAt: (Double) -> Double = { 30.0 },
    val sampleRateHz: Double = 100.0,
    val wheelbaseM: Double = 1.25,
    val headingDeg: Double = 45.0,
    val startLat: Double = 18.5204,
    val startLon: Double = 73.8567,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
    val verticalNoiseMps2: Double = 0.3,
    val horizontalNoiseMps2: Double = 0.3,
    val gyroNoiseRadPerS: Double = 0.02,
    val seed: Long = 42L,
    val accuracyM: Float? = 5f,
    val hasGravitySensor: Boolean = true,
    val reportsSpeed: Boolean = true,
    val reportsBearing: Boolean = true,
    val bumps: List<SyntheticBump> = emptyList(),
    val roughPatches: List<RoughPatch> = emptyList(),
    val handling: List<TimeInterval> = emptyList(),
    val handlingGyroRadPerS: Double = 3.0,
    val gpsDropouts: List<TimeInterval> = emptyList(),
    val inVehicle: Boolean? = null,
    val gpsIntervalMs: Long = 1_000L,
    val baseElapsedNanos: Long = 5_000_000_000_000L,
    val baseWallTimeMs: Long = 1_760_000_000_000L,
) {
    fun labels(): List<TraceLabel> {
        val bumpLabels = bumps.mapNotNull { it.label }
        val patchLabels =
            roughPatches.filter { it.isLabeled }.map {
                TraceLabel((it.startS * 1000.0).roundToLong(), TraceLabel.ROUGH_PATCH, durationMs = (it.durationS * 1000.0).roundToLong())
            }
        return (bumpLabels + patchLabels).sortedBy { it.tMs }
    }

    fun run(
        vehicleType: VehicleType,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): TraceOutput {
        val listener = CollectingListener()
        val detector = RoadDetector.create(vehicleType, listener, config)
        val fixes = feed(detector, flush = true)
        return TraceOutput(listener.events.toList(), listener.windows.toList(), fixes, baseElapsedNanos)
    }

    /** Feeds the whole trace into [detector] and returns the fixes that were sent. */
    fun feed(
        detector: RoadDetector,
        flush: Boolean,
    ): List<LocationFix> {
        val random = Random(seed)
        val rotation = deviceToWorld()
        val sampleCount = (durationS * sampleRateHz).toInt()
        val dtS = 1.0 / sampleRateHz
        val fixes = mutableListOf<LocationFix>()
        var nextFixMs = 0L
        var distanceM = 0.0
        var previousSpeedMps = speedKmhAt(0.0) / KMH_PER_MPS
        val headingRad = Math.toRadians(headingDeg)
        detector.onInVehicle(inVehicle)
        for (i in 0 until sampleCount) {
            val tS = i * dtS
            val speedMps = speedKmhAt(tS) / KMH_PER_MPS
            if (i > 0) distanceM += 0.5 * (speedMps + previousSpeedMps) * dtS
            val elapsedNanos = baseElapsedNanos + (tS * 1.0e9).roundToLong()
            while (nextFixMs <= tS * 1000.0 + 1.0e-6) {
                val fixS = nextFixMs / 1000.0
                if (gpsDropouts.none { it.contains(fixS) }) {
                    val fix =
                        LocationFix(
                            elapsedNanos = baseElapsedNanos + nextFixMs * NANOS_PER_MS,
                            wallTimeMs = baseWallTimeMs + nextFixMs,
                            lat = GeoMath.offsetLat(startLat, distanceM, headingDeg),
                            lon = GeoMath.offsetLon(startLat, startLon, distanceM, headingDeg),
                            speedMps = if (reportsSpeed) speedMps.toFloat() else null,
                            accuracyM = accuracyM,
                            bearingDeg = if (reportsBearing) headingDeg.toFloat() else null,
                        )
                    fixes += fix
                    detector.onLocation(fix)
                }
                nextFixMs += gpsIntervalMs
            }

            val noiseMultiplier = roughPatches.firstOrNull { tS >= it.startS && tS < it.startS + it.durationS }?.noiseMultiplier ?: 1.0
            val verticalMps2 = verticalNoiseMps2 * noiseMultiplier * random.nextGaussian() + bumpsAt(tS)
            val longitudinalMps2 = (speedMps - previousSpeedMps) / dtS
            val eastMps2 = longitudinalMps2 * sin(headingRad) + horizontalNoiseMps2 * random.nextGaussian()
            val northMps2 = longitudinalMps2 * cos(headingRad) + horizontalNoiseMps2 * random.nextGaussian()
            val upMps2 = GRAVITY_MPS2 + verticalMps2
            previousSpeedMps = speedMps

            val isHandling = handling.any { it.contains(tS) }
            val gyroX = gyroNoiseRadPerS * random.nextGaussian() + if (isHandling) handlingGyroRadPerS else 0.0
            val gyroY = gyroNoiseRadPerS * random.nextGaussian()
            val gyroZ = gyroNoiseRadPerS * random.nextGaussian()
            detector.onGyroscope(elapsedNanos, gyroX.toFloat(), gyroY.toFloat(), gyroZ.toFloat())
            if (hasGravitySensor) {
                detector.onGravity(
                    elapsedNanos,
                    toDevice(rotation, 0, 0.0, 0.0, GRAVITY_MPS2).toFloat(),
                    toDevice(rotation, 1, 0.0, 0.0, GRAVITY_MPS2).toFloat(),
                    toDevice(rotation, 2, 0.0, 0.0, GRAVITY_MPS2).toFloat(),
                )
            }
            detector.onAccelerometer(
                elapsedNanos,
                toDevice(rotation, 0, eastMps2, northMps2, upMps2).toFloat(),
                toDevice(rotation, 1, eastMps2, northMps2, upMps2).toFloat(),
                toDevice(rotation, 2, eastMps2, northMps2, upMps2).toFloat(),
            )
        }
        if (flush) detector.flush()
        return fixes
    }

    /** World vertical acceleration of all injected bumps (front and rear wheel) at [tS]. */
    fun bumpsAt(tS: Double): Double {
        var sum = 0.0
        for (bump in bumps) {
            sum += shapeAt(bump, tS - bump.tS, bump.amplitudeMps2)
            if (bump.rearWheelFactor > 0.0) {
                val speedMps = speedKmhAt(bump.tS) / KMH_PER_MPS
                if (speedMps > 0.0) {
                    val delayS = wheelbaseM / speedMps
                    sum += shapeAt(bump, tS - bump.tS - delayS, bump.amplitudeMps2 * bump.rearWheelFactor)
                }
            }
        }
        return sum
    }

    private fun shapeAt(
        bump: SyntheticBump,
        offsetS: Double,
        amplitudeMps2: Double,
    ): Double {
        val durationS = bump.durationMs / 1000.0
        return when (bump.shape) {
            BumpShape.IMPACT -> {
                val startS = -durationS / 2.0
                val reboundStartS = durationS / 2.0
                when {
                    offsetS >= startS && offsetS < reboundStartS -> amplitudeMps2 * sin(PI * (offsetS - startS) / durationS)
                    offsetS >= reboundStartS && offsetS < reboundStartS + durationS ->
                        -SyntheticBump.REBOUND_FACTOR * amplitudeMps2 * sin(PI * (offsetS - reboundStartS) / durationS)
                    else -> 0.0
                }
            }
            BumpShape.HUMP -> {
                val startS = -durationS / 2.0
                if (offsetS >= startS && offsetS < durationS / 2.0) {
                    amplitudeMps2 * cos(2.0 * PI * (offsetS - startS) / durationS)
                } else {
                    0.0
                }
            }
        }
    }

    private fun deviceToWorld(): Array<DoubleArray> {
        val r = Math.toRadians(rollDeg)
        val p = Math.toRadians(pitchDeg)
        val y = Math.toRadians(yawDeg)
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(r), -sin(r)), doubleArrayOf(0.0, sin(r), cos(r)))
        val ry = arrayOf(doubleArrayOf(cos(p), 0.0, sin(p)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(p), 0.0, cos(p)))
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        return multiply(rz, multiply(ry, rx))
    }

    private fun multiply(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
    ): Array<DoubleArray> =
        Array(3) { row ->
            DoubleArray(3) { col -> a[row][0] * b[0][col] + a[row][1] * b[1][col] + a[row][2] * b[2][col] }
        }

    /** Component [axis] of R^T * world vector. */
    private fun toDevice(
        rotation: Array<DoubleArray>,
        axis: Int,
        east: Double,
        north: Double,
        up: Double,
    ): Double = rotation[0][axis] * east + rotation[1][axis] * north + rotation[2][axis] * up

    companion object {
        const val GRAVITY_MPS2 = 9.80665
    }
}

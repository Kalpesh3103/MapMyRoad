package com.mapmyroad.detector

/**
 * Current gravity vector in the device frame (ARCHITECTURE.md section 5 step 2).
 *
 * Uses Sensor.TYPE_GRAVITY when it is fresh. If no gravity sample was seen for longer than the stale limit (or
 * never), it falls back to a time-constant low-pass of the accelerometer, which runs on every accelerometer sample
 * so it is already settled when it is needed. The low-pass coefficient comes from the measured sample interval.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class GravityEstimator(
    lowPassTimeConstantS: Double,
    private val staleNanos: Long,
) {
    private val lowPassX = LowPassFilter(lowPassTimeConstantS)
    private val lowPassY = LowPassFilter(lowPassTimeConstantS)
    private val lowPassZ = LowPassFilter(lowPassTimeConstantS)
    private var sensorX = 0.0
    private var sensorY = 0.0
    private var sensorZ = 0.0
    private var lastSensorNanos = 0L
    private var hasSensorSample = false

    /** Estimate after the latest [onAccelerometer] call, m/s^2, device frame. */
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set

    /** True when the latest estimate came from the TYPE_GRAVITY sensor rather than the fallback. */
    var isFromSensor = false
        private set

    fun onGravitySensor(
        elapsedNanos: Long,
        gx: Double,
        gy: Double,
        gz: Double,
    ) {
        sensorX = gx
        sensorY = gy
        sensorZ = gz
        lastSensorNanos = elapsedNanos
        hasSensorSample = true
    }

    fun onAccelerometer(
        elapsedNanos: Long,
        ax: Double,
        ay: Double,
        az: Double,
        dtS: Double,
    ) {
        lowPassX.filter(ax, dtS)
        lowPassY.filter(ay, dtS)
        lowPassZ.filter(az, dtS)
        isFromSensor = hasSensorSample && elapsedNanos - lastSensorNanos <= staleNanos
        if (isFromSensor) {
            x = sensorX
            y = sensorY
            z = sensorZ
        } else {
            x = lowPassX.value()
            y = lowPassY.value()
            z = lowPassZ.value()
        }
    }

    /** Restarts the fallback low-pass (after a sensor gap). The last gravity sensor sample is kept. */
    fun restartLowPass() {
        lowPassX.reset()
        lowPassY.reset()
        lowPassZ.reset()
    }

    fun reset() {
        restartLowPass()
        sensorX = 0.0
        sensorY = 0.0
        sensorZ = 0.0
        lastSensorNanos = 0L
        hasSensorSample = false
        x = 0.0
        y = 0.0
        z = 0.0
        isFromSensor = false
    }
}

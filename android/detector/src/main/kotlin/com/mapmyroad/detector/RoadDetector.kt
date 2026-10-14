package com.mapmyroad.detector

/**
 * Detection pipeline entry point (ARCHITECTURE.md section 5).
 *
 * Threading: not thread-safe. Feed every method from one thread (the tracking service's sensor thread).
 * Sensor methods take primitives and must not allocate (CODING_STANDARDS.md section 3.6). Timestamps are
 * elapsed-realtime nanoseconds (SensorEvent.timestamp, Location.elapsedRealtimeNanos).
 *
 * Deterministic: the same input sequence always produces the same output.
 */
interface RoadDetector {
    /** Raw accelerometer including gravity, m/s^2 (Sensor.TYPE_ACCELEROMETER). */
    fun onAccelerometer(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    )

    /** Angular velocity, rad/s (Sensor.TYPE_GYROSCOPE). */
    fun onGyroscope(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    )

    /** Gravity vector, m/s^2 (Sensor.TYPE_GRAVITY). If the device has none, the detector estimates it. */
    fun onGravity(
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    )

    fun onLocation(fix: LocationFix)

    /** Activity hint from Activity Recognition when available. Null means unknown (gating then relies on speed). */
    fun onInVehicle(inVehicle: Boolean?)

    /** Emits the last partial roughness window if it is long enough, then clears all state. */
    fun flush()

    /** Clears all state without emitting, for example after a pause. */
    fun reset()

    companion object {
        fun create(
            vehicleType: VehicleType,
            listener: DetectorListener,
            config: DetectorConfig = DetectorConfig.DEFAULT,
        ): RoadDetector = DefaultRoadDetector(vehicleType, listener, config)
    }
}

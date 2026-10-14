package com.mapmyroad.sensorlogger.core

/** Vehicle codes. Must match API_SPEC.md section 1.1 `vehicleType`. */
enum class VehicleType(
    val code: String,
) {
    MOTORCYCLE("motorcycle"),
    SCOOTER("scooter"),
    CAR("car"),
    AUTO_RICKSHAW("auto_rickshaw"),
    ;

    companion object {
        fun fromCode(code: String?): VehicleType? = entries.firstOrNull { it.code == code }
    }
}

/** Where the phone was during the session (TESTING_STRATEGY.md section 3.4 step 2). */
enum class PhonePlacement(
    val code: String,
) {
    MOUNT("mount"),
    POCKET("pocket"),
    BAG("bag"),
    OTHER("other"),
    ;

    companion object {
        fun fromCode(code: String?): PhonePlacement? = entries.firstOrNull { it.code == code }
    }
}

/** Manual tag types written to tags.csv `type`. */
enum class TagType(
    val code: String,
) {
    POTHOLE("pothole"),
    SPEED_BREAKER("speed_breaker"),
    ROUGH_PATCH_START("rough_patch_start"),
    ROUGH_PATCH_END("rough_patch_end"),
    OTHER("other"),
    ;

    companion object {
        fun fromCode(code: String?): TagType? = entries.firstOrNull { it.code == code }
    }
}

/** Sensor stream codes written to sensors.csv `sensor`. */
enum class SensorStream(
    val code: String,
) {
    ACC("acc"),
    GYRO("gyro"),
    GRAV("grav"),
    ROT("rot"),
}

/** Severity values for tags (API_SPEC.md section 1.1): 1 minor, 2 moderate, 3 severe. */
object TagSeverity {
    const val NONE = 0
    const val MINOR = 1
    const val MODERATE = 2
    const val SEVERE = 3

    fun isValid(severity: Int): Boolean = severity in NONE..SEVERE
}

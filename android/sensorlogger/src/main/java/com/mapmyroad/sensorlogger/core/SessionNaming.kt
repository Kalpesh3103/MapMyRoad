package com.mapmyroad.sensorlogger.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Session directory naming: `<yyyyMMdd_HHmmss>_<vehicle>_<placement>` in local time. */
object SessionNaming {
    const val SESSIONS_DIR = "sessions"
    const val SENSORS_FILE = "sensors.csv"
    const val LOCATION_FILE = "location.csv"
    const val TAGS_FILE = "tags.csv"
    const val META_FILE = "meta.json"

    private const val DATE_PATTERN = "yyyyMMdd_HHmmss"

    fun directoryName(
        startWallMs: Long,
        vehicle: VehicleType,
        placement: PhonePlacement,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val format = SimpleDateFormat(DATE_PATTERN, Locale.US)
        format.timeZone = timeZone
        return "${format.format(Date(startWallMs))}_${vehicle.code}_${placement.code}"
    }
}

package com.mapmyroad.detector

import java.io.File

/** A recorded trace fixture (TESTING_STRATEGY.md section 3.1) with its labels. */
internal data class FixtureTrace(
    val name: String,
    val vehicleType: VehicleType,
    val csvFile: File,
    val labels: List<TraceLabel>,
)

/**
 * Loads fixtures from src/test/resources/traces/: `<name>.csv` with the header
 * `t_ms,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,lat,lon,speed_mps,accuracy_m` plus `<name>.labels.json`.
 *
 * Replay rules: every row feeds gyroscope, gravity (when the gravity columns are filled) and accelerometer at t_ms.
 * A fix is sent when lat and lon are filled and the GPS columns differ from the previous fix (GPS repeats at about
 * 1 Hz). Fixes carry no bearing (the detector computes the course); wall time is t_ms. Lines starting with '#' are
 * comments.
 */
internal object FixtureTraces {
    const val RESOURCE_DIR = "traces"
    private const val BASE_ELAPSED_NANOS = 5_000_000_000_000L
    private val expectedHeader =
        listOf("t_ms", "ax", "ay", "az", "gx", "gy", "gz", "grav_x", "grav_y", "grav_z", "lat", "lon", "speed_mps", "accuracy_m")

    /** All fixtures with a labels file, sorted by name; empty when the directory is missing or has no CSV files. */
    fun load(): List<FixtureTrace> {
        val url = FixtureTraces::class.java.classLoader.getResource(RESOURCE_DIR) ?: return emptyList()
        if (url.protocol != "file") return emptyList()
        val dir = File(url.toURI())
        val csvFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".csv") }?.sortedBy { it.name } ?: return emptyList()
        return csvFiles.map { csv ->
            val name = csv.name.removeSuffix(".csv")
            val labelsFile = File(dir, "$name.labels.json")
            require(labelsFile.isFile) { "fixture $name has no labels file $name.labels.json" }
            parseLabels(name, csv, labelsFile.readText())
        }
    }

    fun parseLabels(
        name: String,
        csv: File,
        json: String,
    ): FixtureTrace {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("labels of $name: root must be an object")
        val vehicleCode = root["vehicleType"] as? String ?: throw IllegalArgumentException("labels of $name: missing vehicleType")
        val vehicleType = VehicleType.fromCode(vehicleCode) ?: throw IllegalArgumentException("labels of $name: unknown vehicleType")
        val rawLabels = root["labels"] as? List<*> ?: emptyList<Any?>()
        val labels =
            rawLabels.map { raw ->
                val label = raw as? Map<*, *> ?: throw IllegalArgumentException("labels of $name: label must be an object")
                val tMs = (label["tMs"] as? Double ?: throw IllegalArgumentException("labels of $name: label without tMs")).toLong()
                val type = label["type"] as? String ?: throw IllegalArgumentException("labels of $name: label without type")
                TraceLabel(
                    tMs = tMs,
                    type = type,
                    severity = (label["severity"] as? Double)?.toInt(),
                    durationMs = (label["durationMs"] as? Double)?.toLong(),
                )
            }
        return FixtureTrace(name, vehicleType, csv, labels.sortedBy { it.tMs })
    }

    /** Replays [fixture] into a new detector and returns labels and detections. */
    fun run(
        fixture: FixtureTrace,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): LabeledRun {
        val listener = CollectingListener()
        val detector = RoadDetector.create(fixture.vehicleType, listener, config)
        replay(fixture.csvFile.readLines(), detector)
        detector.flush()
        return LabeledRun(
            name = fixture.name,
            vehicleType = fixture.vehicleType,
            labels = fixture.labels,
            detections = listener.events.map { Detection((it.elapsedNanos - BASE_ELAPSED_NANOS) / 1.0e6, it.severity.level) },
        )
    }

    fun replay(
        lines: List<String>,
        detector: RoadDetector,
    ) {
        var isHeaderSeen = false
        var lastGps: List<String>? = null
        for ((lineIndex, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split(',').map { it.trim() }
            if (!isHeaderSeen) {
                require(cells == expectedHeader) { "line ${lineIndex + 1}: unexpected header" }
                isHeaderSeen = true
                continue
            }
            require(cells.size == expectedHeader.size) { "line ${lineIndex + 1}: expected ${expectedHeader.size} columns" }
            val tMs = cells[0].toDouble()
            val elapsedNanos = BASE_ELAPSED_NANOS + (tMs * 1.0e6).toLong()
            val gps = cells.subList(10, 14)
            if (gps[0].isNotEmpty() && gps[1].isNotEmpty() && gps != lastGps) {
                lastGps = gps
                detector.onLocation(
                    LocationFix(
                        elapsedNanos = elapsedNanos,
                        wallTimeMs = tMs.toLong(),
                        lat = gps[0].toDouble(),
                        lon = gps[1].toDouble(),
                        speedMps = gps[2].toFloatOrNull(),
                        accuracyM = gps[3].toFloatOrNull(),
                        bearingDeg = null,
                    ),
                )
            }
            if (cells[4].isNotEmpty()) detector.onGyroscope(elapsedNanos, cells[4].toFloat(), cells[5].toFloat(), cells[6].toFloat())
            if (cells[7].isNotEmpty()) detector.onGravity(elapsedNanos, cells[7].toFloat(), cells[8].toFloat(), cells[9].toFloat())
            detector.onAccelerometer(elapsedNanos, cells[1].toFloat(), cells[2].toFloat(), cells[3].toFloat())
        }
    }
}

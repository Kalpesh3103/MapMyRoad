package com.mapmyroad.sensorlogger.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class CsvStreamWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesHeaderAndRows_andCountsRows() {
        val file = File(temp.root, "sensors.csv")
        val writer = CsvStreamWriter(file, CsvFormat.SENSORS_HEADER)
        val row = StringBuilder()
        repeat(3) { i ->
            row.setLength(0)
            CsvFormat.appendSensorRow(row, i * 10_000L, "acc", 1f, 2f, 3f, Float.NaN)
            writer.writeRow(row)
        }
        writer.close()

        assertEquals(3L, writer.rowCount)
        assertEquals(
            listOf("t_ms,sensor,x,y,z,w", "0.000,acc,1.0,2.0,3.0,", "10.000,acc,1.0,2.0,3.0,", "20.000,acc,1.0,2.0,3.0,"),
            file.readLines(),
        )
    }

    @Test
    fun handlesRowsLongerThanInitialBuffer() {
        val file = File(temp.root, "long.csv")
        val writer = CsvStreamWriter(file, "h")
        val longRow = StringBuilder().append("x".repeat(1_000)).append('\n')
        writer.writeRow(longRow)
        writer.close()
        assertEquals(1_000, file.readLines()[1].length)
    }

    @Test
    fun zip_containsSessionFilesUnderSessionFolder() {
        val session = temp.newFolder("20261014_083012_scooter_mount")
        File(session, "tags.csv").writeText("t_ms,wall_ms,type,severity\n")
        File(session, "meta.json").writeText("{}")
        val zip = SessionZipper.zip(session, File(temp.root, "out"))

        ZipFile(zip).use { zipFile ->
            val names = zipFile.entries().toList().map { it.name }
            assertEquals(listOf("20261014_083012_scooter_mount/meta.json", "20261014_083012_scooter_mount/tags.csv"), names)
        }
    }
}

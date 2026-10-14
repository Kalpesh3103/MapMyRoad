package com.mapmyroad.sensorlogger.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips one session directory. Blocking: call from a background dispatcher. */
object SessionZipper {
    /**
     * Writes `<outDir>/<sessionDir.name>.zip` containing `<sessionDir.name>/<file>` entries for the
     * regular files of [sessionDir] (no recursion; sessions are flat). Returns the zip file.
     */
    @Throws(IOException::class)
    fun zip(
        sessionDir: File,
        outDir: File,
    ): File {
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Cannot create output directory")
        val zipFile = File(outDir, "${sessionDir.name}.zip")
        val files =
            sessionDir
                .listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                .orEmpty()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (file in files) {
                zos.putNextEntry(ZipEntry("${sessionDir.name}/${file.name}"))
                file.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }
}

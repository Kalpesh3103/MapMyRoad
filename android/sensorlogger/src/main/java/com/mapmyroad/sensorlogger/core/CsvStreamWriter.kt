package com.mapmyroad.sensorlogger.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Append-only CSV file with a 64 KB buffer. Writes the header when created.
 *
 * Threading: NOT thread-safe. In the app exactly one thread (the logger IO HandlerThread) calls
 * every method. [writeRow] copies the row into a reused char array, so it does not allocate.
 */
class CsvStreamWriter(
    file: File,
    header: String,
) {
    private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8), BUFFER_SIZE_CHARS)
    private var chars = CharArray(INITIAL_ROW_CHARS)
    private var isClosed = false

    /** Number of data rows written (header excluded). */
    var rowCount: Long = 0L
        private set

    init {
        writer.write(header)
        writer.write('\n'.code)
    }

    /** Writes the content of [row] (which must already end with '\n') and counts one row. */
    fun writeRow(row: StringBuilder) {
        if (isClosed) return
        val length = row.length
        if (length > chars.size) chars = CharArray(length * 2)
        row.getChars(0, length, chars, 0)
        writer.write(chars, 0, length)
        rowCount++
    }

    fun flush() {
        if (!isClosed) writer.flush()
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        // BufferedWriter.close() flushes the buffer before closing the underlying stream.
        writer.close()
    }

    private companion object {
        const val BUFFER_SIZE_CHARS = 64 * 1024
        const val INITIAL_ROW_CHARS = 256
    }
}

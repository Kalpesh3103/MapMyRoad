package com.mapmyroad.detector

import kotlin.math.max
import kotlin.math.sqrt

/**
 * RMS over a sliding time window, backed by preallocated ring buffers of squares and timestamps with a running
 * sum. The sum is a Double and is recomputed from the buffer once per [capacity] insertions, which bounds any
 * floating-point drift from repeated add/subtract.
 *
 * If more than [capacity] samples fall inside the window (sensor faster than the sizing assumption), the oldest
 * samples are dropped, so the window covers a shorter span but nothing allocates.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free after construction.
 */
internal class RollingRms(
    private val capacity: Int,
    private val windowNanos: Long,
) {
    private val squares = DoubleArray(capacity)
    private val timesNanos = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var sum = 0.0
    private var insertsSinceRecompute = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(windowNanos > 0L) { "windowNanos must be positive" }
    }

    fun add(
        elapsedNanos: Long,
        value: Double,
    ) {
        evictUpTo(elapsedNanos - windowNanos)
        if (size == capacity) removeOldest()
        val square = value * value
        val index = (head + size) % capacity
        squares[index] = square
        timesNanos[index] = elapsedNanos
        size++
        sum += square
        insertsSinceRecompute++
        if (insertsSinceRecompute >= capacity) recompute()
    }

    /** RMS of the samples currently in the window, 0 when empty. */
    fun rms(): Double = if (size == 0) 0.0 else sqrt(max(0.0, sum) / size)

    fun size(): Int = size

    /** Largest |value| among buffered samples with time in [fromNanos, toNanos], 0 when there is none. */
    fun maxAbsBetween(
        fromNanos: Long,
        toNanos: Long,
    ): Double {
        var maxSquare = 0.0
        for (i in 0 until size) {
            val index = (head + i) % capacity
            val time = timesNanos[index]
            if (time in fromNanos..toNanos && squares[index] > maxSquare) maxSquare = squares[index]
        }
        return sqrt(maxSquare)
    }

    fun clear() {
        head = 0
        size = 0
        sum = 0.0
        insertsSinceRecompute = 0
    }

    private fun evictUpTo(cutoffNanos: Long) {
        while (size > 0 && timesNanos[head] <= cutoffNanos) removeOldest()
    }

    private fun removeOldest() {
        sum -= squares[head]
        head = (head + 1) % capacity
        size--
        if (size == 0) sum = 0.0
    }

    private fun recompute() {
        var exact = 0.0
        for (i in 0 until size) exact += squares[(head + i) % capacity]
        sum = exact
        insertsSinceRecompute = 0
    }
}

package com.mapmyroad.detector

/**
 * Fixed-capacity FIFO of detected bumps that wait for the next GPS fix so they can be positioned by interpolation
 * (M2-3). Primitive arrays preallocated at construction; events are appended in time order.
 *
 * Threading: not thread-safe, owned by one detector. Allocation-free.
 */
internal class PendingEventQueue(
    private val capacity: Int,
) {
    private val elapsedNanos = LongArray(capacity)
    private val peakScores = DoubleArray(capacity)
    private val severityLevels = IntArray(capacity)
    private val speedsKmh = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun isEmpty(): Boolean = size == 0

    fun isFull(): Boolean = size == capacity

    fun size(): Int = size

    /** Appends an event. The caller must make room first when [isFull]. */
    fun add(
        eventNanos: Long,
        peakScore: Double,
        severityLevel: Int,
        speedKmh: Double,
    ) {
        check(size < capacity) { "pending queue is full" }
        val index = (head + size) % capacity
        elapsedNanos[index] = eventNanos
        peakScores[index] = peakScore
        severityLevels[index] = severityLevel
        speedsKmh[index] = speedKmh
        size++
    }

    fun oldestElapsedNanos(): Long = elapsedNanos[head]

    fun oldestPeakScore(): Double = peakScores[head]

    fun oldestSeverityLevel(): Int = severityLevels[head]

    fun oldestSpeedKmh(): Double = speedsKmh[head]

    fun removeOldest() {
        check(size > 0) { "pending queue is empty" }
        head = (head + 1) % capacity
        size--
    }

    /** Drops every event at or after [cutoffNanos] (newest first, since the queue is time ordered). */
    fun removeFrom(cutoffNanos: Long) {
        while (size > 0 && elapsedNanos[(head + size - 1) % capacity] >= cutoffNanos) size--
    }

    fun clear() {
        head = 0
        size = 0
    }
}

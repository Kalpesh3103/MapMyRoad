package com.mapmyroad.data.device

/** Wall-clock time in epoch milliseconds, injected so tests control token expiry times. */
fun interface EpochClock {
    fun nowMs(): Long
}

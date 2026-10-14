package com.mapmyroad.detector

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.sin

/** CODING_STANDARDS.md section 3.6: the per-sample path must not allocate. Skipped on JVMs without the counter. */
class HotPathAllocationTest {
    @Test
    fun sensorCallbacks_doNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val listener = CollectingListener()
        val detector = RoadDetector.create(VehicleType.SCOOTER, listener)
        val base = 1_000_000_000_000L
        val sampleCount = 60_000
        val fixes =
            List(sampleCount / 100 + 1) {
                LocationFix(base + it * 1_000_000_000L, it * 1_000L, 18.0 + it * 1e-4, 73.0, 10f, 5f, 0f)
            }
        val threadId = Thread.currentThread().id

        fun feed(
            from: Int,
            to: Int,
        ) {
            for (i in from until to) {
                val t = base + i * 10_000_000L
                if (i % 100 == 0) detector.onLocation(fixes[i / 100])
                val noise = (0.2 * sin(i * 0.7)).toFloat()
                detector.onGyroscope(t, 0.01f, 0.0f, 0.0f)
                detector.onGravity(t, 0.0f, 0.0f, 9.81f)
                detector.onAccelerometer(t, 0.0f, 0.0f, 9.81f + noise)
            }
        }
        feed(0, 20_000)
        val before = bean.getThreadAllocatedBytes(threadId)
        feed(20_000, sampleCount)
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        // 40 000 samples; fixes and the 80 roughness windows allocate a little. Per-sample allocation would be megabytes.
        assertTrue("allocated $allocated bytes", allocated < 200_000)
    }
}

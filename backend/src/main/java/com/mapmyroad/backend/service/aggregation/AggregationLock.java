package com.mapmyroad.backend.service.aggregation;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Serializes the incremental aggregation, the nightly refresh and the recompute-queue consumer within this
 * instance. They update the same hazard rows in multi-row statements; running them one at a time avoids lock
 * waits and deadlocks between them (scheduled tasks run on virtual threads, so they can otherwise overlap).
 */
@Component
public class AggregationLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Runs {@code task} if no other aggregation work is running; returns false when skipped. */
    public boolean tryRun(Runnable task) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Waits for other aggregation work to finish, then runs {@code task} (used by the nightly refresh). */
    public void run(Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}

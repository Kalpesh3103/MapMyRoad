package com.mapmyroad.data.local

/** Values of `upload_queue.state`. */
object UploadQueueState {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val DONE = "done"

    /** Permanent failure (rejected request or retries exhausted). Not retried. */
    const val FAILED = "failed"

    /** Nothing left to upload after trimming and validation (for example a trip under 1 km). */
    const val SKIPPED = "skipped"

    /** `last_error` of a row skipped because the trip started before contribution was (last) switched on. */
    const val CATEGORY_BEFORE_OPT_IN = "before_opt_in"
}

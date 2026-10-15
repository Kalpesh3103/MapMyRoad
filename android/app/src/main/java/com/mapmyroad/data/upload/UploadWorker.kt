package com.mapmyroad.data.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background upload of finished trips (M5-4). Scheduled by [WorkManagerUploadScheduler] with network and battery
 * constraints and exponential backoff; the work itself is in [UploadRunner].
 */
@HiltWorker
class UploadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val runner: UploadRunner,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            when (runner.run()) {
                UploadRunOutcome.SUCCESS -> Result.success()
                UploadRunOutcome.RETRY -> Result.retry()
                UploadRunOutcome.FAILURE -> Result.failure()
            }
    }

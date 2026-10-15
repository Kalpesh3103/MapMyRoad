package com.mapmyroad.data.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.mapmyroad.data.local.UploadDao
import com.mapmyroad.di.ApplicationScope
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.upload.UploadNetwork
import com.mapmyroad.domain.upload.UploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * [UploadScheduler] with one unique WorkManager job, [UNIQUE_WORK_NAME] (API_SPEC.md section 6):
 * - Network: unmetered (Wi-Fi) by default, any connection only when the user allowed mobile data.
 * - Battery not low. Exponential backoff from [INITIAL_BACKOFF_S] (WorkManager caps it at 5 hours).
 * - A random start delay of up to [MAX_START_JITTER_S] spreads uploads of many phones (WorkManager backoff has no
 *   jitter of its own).
 *
 * Policy: [ExistingWorkPolicy.KEEP] for new trips. One run drains the whole queue and re-reads it until nothing is
 * due, so a second job adds nothing, and KEEP neither cancels an upload in flight nor resets a running backoff.
 * [ExistingWorkPolicy.REPLACE] only when the network setting changed, because constraints of enqueued work cannot be
 * changed in place; cancelling a run mid-way is safe since item IDs make uploads idempotent.
 *
 * WorkManager is looked up lazily: it initializes from MapMyRoadApp's configuration, which needs injected fields.
 */
@Singleton
class WorkManagerUploadScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val uploadDao: UploadDao,
        private val clock: UploadClock,
        @ApplicationScope private val scope: CoroutineScope,
    ) : UploadScheduler {
        override fun scheduleUploads() {
            scope.launch { enqueue(ExistingWorkPolicy.KEEP) }
        }

        /** Re-enqueues with the current constraints, replacing the job (after "Upload on mobile data" changed). */
        fun rescheduleWithCurrentSettings() {
            scope.launch { enqueue(ExistingWorkPolicy.REPLACE) }
        }

        override fun cancelUploads() {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private suspend fun enqueue(policy: ExistingWorkPolicy) {
            val settings = settingsRepository.settings.first()
            if (!settings.contributionEnabled) return
            val enabledAtMs = settings.contributionEnabledAtMs ?: return
            uploadDao.refreshQueue(clock.nowMs(), enabledAtMs)
            val request = buildRequest(UploadNetwork.forSettings(settings.uploadOnMobileData))
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }

        private fun buildRequest(network: UploadNetwork): OneTimeWorkRequest {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(network.toNetworkType())
                    .setRequiresBatteryNotLow(true)
                    .build()
            return OneTimeWorkRequest
                .Builder(UploadWorker::class.java)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_S, TimeUnit.SECONDS)
                .setInitialDelay(Random.nextLong(0L, MAX_START_JITTER_S + 1), TimeUnit.SECONDS)
                .build()
        }

        companion object {
            const val UNIQUE_WORK_NAME = "trip-uploads"
            const val INITIAL_BACKOFF_S = 30L
            const val MAX_START_JITTER_S = 30L
        }
    }

internal fun UploadNetwork.toNetworkType(): NetworkType =
    when (this) {
        UploadNetwork.UNMETERED -> NetworkType.UNMETERED
        UploadNetwork.CONNECTED -> NetworkType.CONNECTED
    }

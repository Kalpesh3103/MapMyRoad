package com.mapmyroad.sensorlogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mapmyroad.sensorlogger.MainActivity
import com.mapmyroad.sensorlogger.R
import com.mapmyroad.sensorlogger.core.PhonePlacement
import com.mapmyroad.sensorlogger.core.TagSeverity
import com.mapmyroad.sensorlogger.core.TagType
import com.mapmyroad.sensorlogger.core.VehicleType

/**
 * Foreground service (type `location`) that owns one recording session.
 *
 * Threading model (CODING_STANDARDS.md section 3.6):
 * - Main thread: onStartCommand/onDestroy, the foreground notification and the wake lock.
 *   [engine], [ioThread] and [ioHandler] are only read and written on the main thread.
 * - IO thread ([ioThread], "mmr-logger-io"): everything inside [RecordingEngine]. Start, tags and
 *   stop are posted to it with [ioHandler]; sensor and location callbacks are delivered on it.
 * - Stop: the engine's stop is posted, then the thread is quit safely and joined (bounded), so
 *   files are closed on the IO thread before the service stops itself.
 *
 * Tags arrive as [ACTION_TAG] Intents (robust even if the Activity is recreated). The tap time is
 * captured in the Activity, so delivery delay does not shift the tag.
 * START_NOT_STICKY: if the system kills the process, we do not silently start a new session; the
 * periodically flushed files keep the partial data.
 */
class LoggerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: RecordingEngine? = null
    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_TAG -> handleTag(intent, startId)
            ACTION_STOP -> stopRecording(error = null)
            else -> if (engine == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording(error = null)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        // startForeground first, before any validation (and even when already recording): every
        // startForegroundService call must be followed by startForeground or the system crashes the app.
        try {
            createChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            // For example SecurityException (location permission missing on API 34+) or
            // ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "startForeground failed", e)
            LoggerState.publish(LoggerStatus(isRecording = false, error = LoggerError.FOREGROUND_START_FAILED))
            stopSelf()
            return
        }
        if (engine != null) return
        val config = configFrom(intent)
        if (config == null) {
            stopRecording(error = null)
            return
        }
        if (config.keepCpuAwake) acquireWakeLock()

        val thread = HandlerThread("mmr-logger-io", Process.THREAD_PRIORITY_FOREGROUND)
        thread.start()
        val handler = Handler(thread.looper)
        val newEngine =
            RecordingEngine(this, config, handler) { error ->
                mainHandler.post { stopRecording(error) }
            }
        ioThread = thread
        ioHandler = handler
        engine = newEngine
        handler.post { newEngine.start() }
    }

    private fun handleTag(
        intent: Intent,
        startId: Int,
    ) {
        val currentEngine = engine
        val handler = ioHandler
        if (currentEngine == null || handler == null) {
            // Not recording (for example a stale tap after the session ended): do not linger.
            stopSelf(startId)
            return
        }
        val type = TagType.fromCode(intent.getStringExtra(EXTRA_TAG_TYPE)) ?: return
        val severity = intent.getIntExtra(EXTRA_TAG_SEVERITY, TagSeverity.NONE)
        val tapElapsedNanos = intent.getLongExtra(EXTRA_TAP_ELAPSED_NANOS, -1L)
        val tapWallMs = intent.getLongExtra(EXTRA_TAP_WALL_MS, System.currentTimeMillis())
        if (tapElapsedNanos < 0L) return
        handler.post { currentEngine.recordTag(type, severity, tapElapsedNanos, tapWallMs) }
    }

    /** Every stop path ends here: unregisters, closes files, releases the wake lock, stops the service. */
    private fun stopRecording(error: LoggerError?) {
        val currentEngine = engine
        val thread = ioThread
        val handler = ioHandler
        engine = null
        ioThread = null
        ioHandler = null
        if (currentEngine != null && thread != null && handler != null) {
            handler.post { currentEngine.stop(error) }
            thread.quitSafely()
            try {
                thread.join(IO_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        // Timeout is a safety net only; it is far longer than any calibration drive.
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) lock.release()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                immutable,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, LoggerService::class.java).setAction(ACTION_STOP),
                immutable,
            )
        // Generic text only: no location on the lock screen (SECURITY.md section 8).
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logger)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // The system renders the elapsed time, so no periodic notification updates are needed.
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stat_logger, getString(R.string.action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "MmrLoggerService"
        private const val ACTION_START = "com.mapmyroad.sensorlogger.action.START"
        private const val ACTION_STOP = "com.mapmyroad.sensorlogger.action.STOP"
        private const val ACTION_TAG = "com.mapmyroad.sensorlogger.action.TAG"
        private const val EXTRA_VEHICLE = "vehicle"
        private const val EXTRA_PLACEMENT = "placement"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_KEEP_CPU_AWAKE = "keepCpuAwake"
        private const val EXTRA_TAG_TYPE = "tagType"
        private const val EXTRA_TAG_SEVERITY = "tagSeverity"
        private const val EXTRA_TAP_ELAPSED_NANOS = "tapElapsedNanos"
        private const val EXTRA_TAP_WALL_MS = "tapWallMs"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val WAKE_LOCK_TAG = "MmrSensorLogger:recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
        private const val IO_JOIN_TIMEOUT_MS = 2_000L
        private const val MAX_NOTE_CHARS = 200

        fun startIntent(
            context: Context,
            config: RecordingConfig,
        ): Intent =
            Intent(context, LoggerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VEHICLE, config.vehicle.code)
                .putExtra(EXTRA_PLACEMENT, config.placement.code)
                .putExtra(EXTRA_NOTE, config.note.take(MAX_NOTE_CHARS))
                .putExtra(EXTRA_KEEP_CPU_AWAKE, config.keepCpuAwake)

        fun stopIntent(context: Context): Intent = Intent(context, LoggerService::class.java).setAction(ACTION_STOP)

        /** [tapElapsedNanos] must be SystemClock.elapsedRealtimeNanos() taken when the button was tapped. */
        fun tagIntent(
            context: Context,
            type: TagType,
            severity: Int,
            tapElapsedNanos: Long,
            tapWallMs: Long,
        ): Intent =
            Intent(context, LoggerService::class.java)
                .setAction(ACTION_TAG)
                .putExtra(EXTRA_TAG_TYPE, type.code)
                .putExtra(EXTRA_TAG_SEVERITY, severity)
                .putExtra(EXTRA_TAP_ELAPSED_NANOS, tapElapsedNanos)
                .putExtra(EXTRA_TAP_WALL_MS, tapWallMs)

        private fun configFrom(intent: Intent): RecordingConfig? {
            val vehicle = VehicleType.fromCode(intent.getStringExtra(EXTRA_VEHICLE)) ?: return null
            val placement = PhonePlacement.fromCode(intent.getStringExtra(EXTRA_PLACEMENT)) ?: return null
            return RecordingConfig(
                vehicle = vehicle,
                placement = placement,
                note = intent.getStringExtra(EXTRA_NOTE).orEmpty(),
                keepCpuAwake = intent.getBooleanExtra(EXTRA_KEEP_CPU_AWAKE, true),
            )
        }
    }
}

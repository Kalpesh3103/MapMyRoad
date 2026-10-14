package com.mapmyroad.sensing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mapmyroad.MainActivity
import com.mapmyroad.R

/**
 * Builds the persistent trip notification (UI_UX_GUIDELINES.md section 13): "Recording road quality. Tap to open."
 * with Pause (or Resume) and Stop actions.
 *
 * Lock-screen privacy (SECURITY.md section 8): VISIBILITY_PUBLIC with generic text only. The notification never
 * contains a location, distance or place name, so it is safe to show in full on the lock screen.
 *
 * Threading: main thread (called by [TripTrackingService]).
 */
class TripNotificationFactory(
    private val context: Context,
) {
    fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tracking_channel_name),
                // Low importance: no sound, no heads-up, no vibration while riding (UI_UX_GUIDELINES.md section 6).
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.tracking_channel_description)
                setShowBadge(false)
            }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** [startedAtMs] drives the system-rendered elapsed time, so the notification needs no periodic updates. */
    fun build(
        startedAtMs: Long,
        isPaused: Boolean,
    ): Notification {
        val title = if (isPaused) R.string.tracking_notification_title_paused else R.string.tracking_notification_title
        val toggleAction =
            if (isPaused) {
                NotificationCompat.Action(
                    R.drawable.ic_tracking_resume,
                    context.getString(R.string.tracking_action_resume),
                    servicePendingIntent(REQUEST_RESUME, TripTrackingService.resumeIntent(context)),
                )
            } else {
                NotificationCompat.Action(
                    R.drawable.ic_tracking_pause,
                    context.getString(R.string.tracking_action_pause),
                    servicePendingIntent(REQUEST_PAUSE, TripTrackingService.pauseIntent(context)),
                )
            }
        val stopAction =
            NotificationCompat.Action(
                R.drawable.ic_tracking_stop,
                context.getString(R.string.tracking_action_stop),
                servicePendingIntent(REQUEST_STOP, TripTrackingService.stopIntent(context)),
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(R.string.tracking_notification_text))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(startedAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        // Same as tapping the launcher icon: brings the existing task to the front instead of stacking a new one.
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PENDING_INTENT_FLAGS)
    }

    private fun servicePendingIntent(
        requestCode: Int,
        intent: Intent,
    ): PendingIntent = PendingIntent.getService(context, requestCode, intent, PENDING_INTENT_FLAGS)

    companion object {
        const val CHANNEL_ID = "trip_recording"
        const val NOTIFICATION_ID = 1001

        private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val REQUEST_OPEN = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
        private const val REQUEST_STOP = 4
    }
}

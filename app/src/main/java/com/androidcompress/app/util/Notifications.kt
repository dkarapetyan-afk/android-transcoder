package com.androidcompress.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.androidcompress.app.MainActivity
import com.androidcompress.app.R

object Notifications {
    const val RECORD_CHANNEL = "record"
    const val RECORD_QUIET_CHANNEL = "record_quiet"
    const val ENCODE_CHANNEL = "encode"
    const val RECORD_ID = 1001
    const val ENCODE_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RECORD_CHANNEL, context.getString(R.string.notif_record_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RECORD_QUIET_CHANNEL,
                context.getString(R.string.notif_record_quiet_channel),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(ENCODE_CHANNEL, context.getString(R.string.notif_encode_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun openApp(context: Context, extra: Pair<String, String>? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extra?.let { putExtra(it.first, it.second) }
        }
        return PendingIntent.getActivity(
            context,
            extra?.second.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun recording(
        context: Context,
        elapsed: String,
        stopIntent: PendingIntent,
        pauseResumeIntent: PendingIntent? = null,
        bookmarkIntent: PendingIntent? = null,
        paused: Boolean = false,
        saving: Boolean = false,
        preparing: Boolean = false,
        quiet: Boolean = false,
    ): Notification {
        ensureChannels(context)
        val title = when {
            saving -> context.getString(R.string.notif_recording_saving)
            preparing -> context.getString(R.string.notif_recording_starting)
            paused -> context.getString(R.string.notif_recording_paused)
            else -> context.getString(R.string.notif_recording)
        }
        val text = if (saving) {
            context.getString(R.string.notif_recording_saving_text)
        } else {
            elapsed
        }
        val channel = if (quiet) RECORD_QUIET_CHANNEL else RECORD_CHANNEL
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(
                if (quiet) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC,
            )
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!saving) {
            if (pauseResumeIntent != null && !preparing) {
                val pauseResumeLabel = if (paused) {
                    context.getString(R.string.action_resume)
                } else {
                    context.getString(R.string.action_pause)
                }
                builder.addAction(0, pauseResumeLabel, pauseResumeIntent)
            }
            if (bookmarkIntent != null && !preparing) {
                builder.addAction(0, context.getString(R.string.record_bookmark), bookmarkIntent)
            }
            builder.addAction(0, context.getString(R.string.action_stop), stopIntent)
        }
        return builder.build()
    }

    fun encoding(
        context: Context,
        jobId: String,
        percent: Int,
        queueIndex: Int,
        queueTotal: Int,
        cancelIntent: PendingIntent,
    ): Notification {
        ensureChannels(context)
        val text = if (queueTotal > 1) {
            context.getString(R.string.notif_encoding_queue, percent, queueIndex, queueTotal)
        } else {
            context.getString(R.string.notif_encoding_percent, percent)
        }
        return NotificationCompat.Builder(context, ENCODE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notif_encoding))
            .setContentText(text)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, "jobId" to jobId))
            .addAction(0, context.getString(R.string.action_cancel), cancelIntent)
            .build()
    }
}

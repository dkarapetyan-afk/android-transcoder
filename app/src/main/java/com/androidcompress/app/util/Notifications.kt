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

    fun recording(context: Context, elapsed: String, stopIntent: PendingIntent): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, RECORD_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.notif_recording))
            .setContentText(elapsed)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            .build()
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
            "$percent% · $queueIndex of $queueTotal"
        } else {
            "$percent%"
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

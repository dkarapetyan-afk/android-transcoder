package com.androidcompress.app.asr

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import com.androidcompress.app.util.Notifications

class CaptionProgressNotifier(
    context: Context,
    private val cancelIntent: PendingIntent,
    private val openExtra: Pair<String, String>? = null,
    private val throttle: CaptionNoticeThrottle = CaptionNoticeThrottle(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun update(fraction: Float, message: String) {
        val tick = throttle.accept(nowMs(), fraction, message) ?: return
        manager.notify(
            Notifications.CAPTIONS_ID,
            Notifications.captions(appContext, tick.percent, tick.message, cancelIntent, openExtra),
        )
    }

    fun clear() {
        throttle.reset()
        manager.cancel(Notifications.CAPTIONS_ID)
    }
}

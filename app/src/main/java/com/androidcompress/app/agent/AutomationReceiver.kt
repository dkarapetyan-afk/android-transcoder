package com.androidcompress.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androidcompress.app.container

/**
 * Exported entry for Tasker / MacroDroid / `am broadcast`.
 * Does not start screen capture. Completes via [AutomationIntents.ACTION_COMPLETED].
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != AutomationIntents.ACTION_COMPRESS &&
            action != AutomationIntents.ACTION_RECORD_STOP &&
            action != AutomationIntents.ACTION_CANCEL_QUEUE
        ) {
            return
        }
        val pending = goAsync()
        try {
            context.container().automation.dispatch(intent) { pending.finish() }
        } catch (_: Throwable) {
            pending.finish()
        }
    }
}

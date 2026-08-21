package com.androidcompress.app.agent

import android.content.Context
import android.content.Intent
import com.androidcompress.app.MainActivity
import com.androidcompress.app.media.AppShortcuts

object AgentLaunch {
    const val EXTRA_OPEN = "com.androidcompress.app.OPEN"
    const val EXTRA_REQUEST_LIBRARY = "com.androidcompress.app.REQUEST_LIBRARY"
    const val EXTRA_JOB_ID = "com.androidcompress.app.JOB_ID"
    const val OPEN_SETTINGS = "settings"
    const val OPEN_RESULT = "result"
    const val OPEN_RECORD = "record"
    const val OPEN_COMPRESS_LATEST = "compress_latest"
    const val OPEN_EXTRACT_AUDIO = "extract_audio"

    data class UiRequest(
        val destination: String,
        val requestLibrary: Boolean = false,
        val jobId: String = "",
        val uri: String = "",
        val nonce: Long = System.nanoTime(),
    )

    fun fromIntent(intent: Intent?): UiRequest? {
        val incoming = intent ?: return null
        val destination = AppShortcuts.destinationFrom(incoming).orEmpty()
        if (destination.isEmpty()) return null
        return UiRequest(
            destination = destination,
            requestLibrary = incoming.getBooleanExtra(EXTRA_REQUEST_LIBRARY, false),
            jobId = incoming.getStringExtra(EXTRA_JOB_ID).orEmpty(),
            uri = incoming.getStringExtra(AppShortcuts.EXTRA_URI).orEmpty(),
        )
    }

    fun openSettings(context: Context, requestLibrary: Boolean) {
        start(context, OPEN_SETTINGS, requestLibrary = requestLibrary)
    }

    fun openResult(context: Context, jobId: String) {
        start(context, OPEN_RESULT, jobId = jobId)
    }

    private fun start(
        context: Context,
        destination: String,
        requestLibrary: Boolean = false,
        jobId: String = "",
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra(EXTRA_OPEN, destination)
            putExtra(EXTRA_REQUEST_LIBRARY, requestLibrary)
            if (jobId.isNotBlank()) putExtra(EXTRA_JOB_ID, jobId)
        }
        context.startActivity(intent)
    }
}

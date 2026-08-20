package com.androidcompress.app.capture

import android.content.Context
import android.content.Intent
import com.androidcompress.app.R
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.di.AppContainer
import java.util.UUID

object RecordingSession {
    suspend fun startAfterConsent(
        context: Context,
        container: AppContainer,
        resultCode: Int,
        data: Intent,
        options: RecordOptions,
    ) {
        val id = UUID.randomUUID().toString()
        val prefs = container.prefs.current()
        container.jobs.upsert(
            CompressJob(
                id = id,
                type = JobType.RECORD,
                status = JobStatus.DRAFT,
                sourceUri = "",
                outputUri = null,
                displayName = container.appContext.getString(R.string.display_name_screen_recording),
                sourceBytes = 0,
                outputBytes = null,
                durationMs = 0,
                width = 0,
                height = 0,
                settingsJson = SettingsJson.encode(
                    EncodeSettings.forPreset(prefs.defaultPreset, prefs.defaultEngine),
                ),
                error = null,
                createdAt = System.currentTimeMillis(),
                finishedAt = null,
            ),
        )
        runCatching { container.history.prune() }
        ScreenRecordService.start(context, id, resultCode, data, options)
    }
}

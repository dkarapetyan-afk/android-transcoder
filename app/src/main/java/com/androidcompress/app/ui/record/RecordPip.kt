package com.androidcompress.app.ui.record

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidcompress.app.R
import com.androidcompress.app.capture.RecordingState
import com.androidcompress.app.capture.ScreenRecordService
import com.androidcompress.app.util.formatDuration

object RecordPip {
    @RequiresApi(26)
    fun params(context: Context, recording: RecordingState): PictureInPictureParams {
        val pause = pendingService(context, 11, ScreenRecordService.ACTION_PAUSE)
        val resume = pendingService(context, 12, ScreenRecordService.ACTION_RESUME)
        val stop = pendingService(context, 13, ScreenRecordService.ACTION_STOP)
        val mark = pendingService(context, 14, ScreenRecordService.ACTION_BOOKMARK)
        val actions = buildList {
            if (recording.capturing) {
                add(
                    remote(
                        context,
                        if (recording.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                        if (recording.paused) R.string.record_resume else R.string.record_pause,
                        if (recording.paused) resume else pause,
                    ),
                )
                add(
                    remote(
                        context,
                        android.R.drawable.ic_input_add,
                        R.string.record_bookmark,
                        mark,
                    ),
                )
            }
            add(
                remote(
                    context,
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.record_stop,
                    stop,
                ),
            )
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions.take(3))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(recording.capturing && recording.pipEnabled)
        }
        return builder.build()
    }

    @RequiresApi(26)
    private fun remote(context: Context, icon: Int, label: Int, intent: PendingIntent): RemoteAction {
        val text = context.getString(label)
        return RemoteAction(Icon.createWithResource(context, icon), text, text, intent)
    }

    private fun pendingService(context: Context, request: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            request,
            Intent(context, ScreenRecordService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

@Composable
fun RecordPipControls(recording: RecordingState) {
    val context = LocalContext.current
    val elapsed by produceState(0L, recording) {
        while (true) {
            value = recording.elapsedMs()
            kotlinx.coroutines.delay(250)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (recording.paused) {
                    stringResource(R.string.record_elapsed_paused, formatDuration(elapsed))
                } else {
                    stringResource(R.string.record_elapsed, formatDuration(elapsed))
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (recording.paused) ScreenRecordService.resume(context)
                        else ScreenRecordService.pause(context)
                    },
                ) {
                    Text(stringResource(if (recording.paused) R.string.record_resume else R.string.record_pause))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { ScreenRecordService.bookmark(context) },
                ) {
                    Text(stringResource(R.string.record_bookmark))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ScreenRecordService.stop(context) },
                ) {
                    Text(stringResource(R.string.record_stop))
                }
            }
        }
    }
}

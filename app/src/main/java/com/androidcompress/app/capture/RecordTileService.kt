package com.androidcompress.app.capture

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.androidcompress.app.R
import com.androidcompress.app.container

class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(qsTile, applicationContext)
    }

    override fun onClick() {
        val recording = applicationContext.container().recording.state.value
        when {
            !recording.active -> launchConsent()
            recording.saving -> Unit
            recording.phase == RecordPhase.REGION || recording.phase == RecordPhase.COUNTDOWN -> {
                ScreenRecordService.stop(this)
            }
            recording.paused -> ScreenRecordService.resume(this)
            else -> ScreenRecordService.pause(this)
        }
        render(qsTile, applicationContext)
    }

    private fun launchConsent() {
        val intent = Intent(this, RecordConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            40,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestListening(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, RecordTileService::class.java),
                )
            }
        }

        fun render(tile: Tile?, context: Context) {
            val t = tile ?: return
            val recording = context.container().recording.state.value
            t.state = if (recording.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.label = context.getString(R.string.tile_record)
            if (Build.VERSION.SDK_INT >= 29) {
                t.subtitle = when {
                    recording.saving -> context.getString(R.string.record_elapsed_saving)
                    recording.phase == RecordPhase.REGION -> context.getString(R.string.record_phase_region)
                    recording.phase == RecordPhase.COUNTDOWN -> context.getString(
                        R.string.record_countdown_n,
                        recording.countdownRemaining,
                    )
                    recording.paused -> context.getString(R.string.record_resume)
                    recording.active -> context.getString(R.string.record_pause)
                    else -> context.getString(R.string.record_start)
                }
            }
            t.updateTile()
        }
    }
}

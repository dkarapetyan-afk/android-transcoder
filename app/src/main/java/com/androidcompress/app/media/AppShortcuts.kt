package com.androidcompress.app.media

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.androidcompress.app.MainActivity
import com.androidcompress.app.R
import com.androidcompress.app.agent.AgentLaunch
import com.androidcompress.app.util.runCatchingLog

object AppShortcuts {
    private const val TAG = "AppShortcuts"
    const val ACTION_RECORD = "com.androidcompress.app.action.SHORTCUT_RECORD"
    const val ACTION_COMPRESS_LATEST = "com.androidcompress.app.action.SHORTCUT_COMPRESS_LATEST"
    const val ACTION_EXTRACT_AUDIO = "com.androidcompress.app.action.SHORTCUT_EXTRACT_AUDIO"

    const val ID_RECORD = "record"
    const val ID_COMPRESS_LATEST = "compress_latest"
    const val ID_EXTRACT_AUDIO = "extract_audio"
    const val ID_DYNAMIC_LATEST = "dynamic_latest_video"

    const val EXTRA_URI = "com.androidcompress.app.SHORTCUT_URI"

    const val SHORT_LABEL_CHARS = 10
    const val LONG_LABEL_CHARS = 25

    fun destinationFrom(intent: Intent?): String? {
        val incoming = intent ?: return null
        return destinationFrom(
            incoming.action,
            incoming.getStringExtra(AgentLaunch.EXTRA_OPEN),
        )
    }

    fun destinationFrom(action: String?, extraOpen: String?): String? = when (action) {
        ACTION_RECORD -> AgentLaunch.OPEN_RECORD
        ACTION_COMPRESS_LATEST -> AgentLaunch.OPEN_COMPRESS_LATEST
        ACTION_EXTRACT_AUDIO -> AgentLaunch.OPEN_EXTRACT_AUDIO
        else -> extraOpen?.trim()?.ifBlank { null }
    }

    fun shortcutIdFor(destination: String): String? = when (destination) {
        AgentLaunch.OPEN_RECORD -> ID_RECORD
        AgentLaunch.OPEN_COMPRESS_LATEST -> ID_COMPRESS_LATEST
        AgentLaunch.OPEN_EXTRACT_AUDIO -> ID_EXTRACT_AUDIO
        else -> null
    }

    fun activityIntent(context: Context, action: String, uri: String? = null): Intent =
        Intent(action).setClass(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            if (!uri.isNullOrBlank()) putExtra(EXTRA_URI, uri)
        }

    fun publishDynamic(context: Context, latestDisplayName: String?, latestUri: String? = null) {
        if (Build.VERSION.SDK_INT < 25) return
        val name = latestDisplayName?.trim().orEmpty()
        val shortcuts = if (name.isBlank()) {
            emptyList()
        } else {
            listOf(
                ShortcutInfoCompat.Builder(context, ID_DYNAMIC_LATEST)
                    .setShortLabel(LatestVideo.shorten(name, SHORT_LABEL_CHARS))
                    .setLongLabel(
                        LatestVideo.shorten(
                            context.getString(R.string.shortcut_compress_named, name),
                            LONG_LABEL_CHARS,
                        ),
                    )
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_compress))
                    .setIntent(activityIntent(context, ACTION_COMPRESS_LATEST, latestUri))
                    .setRank(0)
                    .build(),
            )
        }
        runCatchingLog(TAG, "set shortcuts") { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun reportUsed(context: Context, destination: String) {
        val id = shortcutIdFor(destination) ?: return
        runCatchingLog(TAG, "report shortcut") { ShortcutManagerCompat.reportShortcutUsed(context, id) }
        if (destination == AgentLaunch.OPEN_COMPRESS_LATEST) {
            runCatchingLog(TAG, "report latest shortcut") {
                ShortcutManagerCompat.reportShortcutUsed(context, ID_DYNAMIC_LATEST)
            }
        }
    }
}

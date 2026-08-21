package com.androidcompress.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.androidcompress.app.agent.AgentLaunch
import com.androidcompress.app.media.ShareIntents
import com.androidcompress.app.media.ShareRequest
import com.androidcompress.app.ui.CompressApp
import com.androidcompress.app.ui.record.RecordPip
import com.androidcompress.app.ui.theme.RecordingCompressorTheme
import com.androidcompress.app.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val _shareRequests = MutableStateFlow<ShareRequest?>(null)
    private val shareRequests = _shareRequests.asStateFlow()
    private val _agentUiRequests = MutableStateFlow<AgentLaunch.UiRequest?>(null)
    private val agentUiRequests = _agentUiRequests.asStateFlow()
    private val _pipMode = MutableStateFlow(false)
    private val pipMode = _pipMode.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            offerShare(intent)
            offerAgentUi(intent)
        }
        setContent {
            RecordingCompressorTheme {
                CompressApp(
                    shareRequests = shareRequests,
                    onShareConsumed = { nonce ->
                        _shareRequests.value = _shareRequests.value?.takeUnless { it.nonce == nonce }
                    },
                    agentUiRequests = agentUiRequests,
                    onAgentUiConsumed = { nonce ->
                        _agentUiRequests.value = _agentUiRequests.value?.takeUnless { it.nonce == nonce }
                    },
                    pipMode = pipMode,
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterRecordPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pipMode.value = isInPictureInPictureMode
    }

    private fun enterRecordPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val recording = container().recording.state.value
        if (!recording.capturing || !recording.pipEnabled || isInPictureInPictureMode) return
        enterPictureInPictureMode(RecordPip.params(this, recording))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerShare(intent)
        offerAgentUi(intent)
    }

    private fun offerAgentUi(incoming: Intent?) {
        val intent = incoming ?: return
        if (intent.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            _agentUiRequests.value = AgentLaunch.UiRequest(AgentLaunch.OPEN_RECORD)
            return
        }
        _agentUiRequests.value = AgentLaunch.fromIntent(intent) ?: return
    }

    private fun offerShare(incoming: Intent?) {
        val intent = incoming ?: return
        val uris = ShareIntents.urisFrom(intent)
        if (uris.isEmpty()) return
        _shareRequests.value = ShareRequest(uris = uris, mimeType = intent.type)
    }
}

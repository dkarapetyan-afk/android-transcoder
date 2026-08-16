package com.androidcompress.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.androidcompress.app.media.ShareIntents
import com.androidcompress.app.media.ShareRequest
import com.androidcompress.app.ui.CompressApp
import com.androidcompress.app.ui.theme.RecordingCompressorTheme
import com.androidcompress.app.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val shareRequests = MutableStateFlow<ShareRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            offerShare(intent)
        }
        setContent {
            RecordingCompressorTheme {
                CompressApp(
                    shareRequests = shareRequests.asStateFlow(),
                    onShareConsumed = { nonce ->
                        shareRequests.value = shareRequests.value?.takeUnless { it.nonce == nonce }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerShare(intent)
    }

    private fun offerShare(incoming: Intent?) {
        val intent = incoming ?: return
        val uris = ShareIntents.urisFrom(intent)
        if (uris.isEmpty()) return
        shareRequests.value = ShareRequest(uris = uris, mimeType = intent.type)
    }
}

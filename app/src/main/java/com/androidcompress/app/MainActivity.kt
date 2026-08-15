package com.androidcompress.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.androidcompress.app.ui.CompressApp
import com.androidcompress.app.ui.theme.RecordingCompressorTheme
import com.androidcompress.app.util.Notifications

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        enableEdgeToEdge()
        setContent {
            RecordingCompressorTheme {
                CompressApp()
            }
        }
    }
}

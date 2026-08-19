package com.androidcompress.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidcompress.app.ui.components.AppTopBar

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar("About", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Recording Compressor", style = MaterialTheme.typography.headlineSmall)
            Text("Compress screen recordings on this device with FFmpeg or the device encoder (Media3). Combine a picture or video with a separate soundtrack. Files are not uploaded.")
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(
                "The app does not collect accounts, analytics, or advertising identifiers. " +
                    "Screen recording uses Android’s MediaProjection consent dialog. " +
                    "Microphone and internal-audio capture are used only when you turn them on. " +
                    "Compressed files are written to Movies/RecordingCompressor (MP4 or WebM) or Music/RecordingCompressor (M4A or WebM audio) on this device. " +
                    "If you add a Gemini API key and tap Generate extra args, the text prompt and encode settings are sent to Google’s Gemini API. The video itself stays on the device. " +
                    "On Android 16+, a privileged system assistant can list jobs, change encode settings, start or wait for work, share or open a finished file, and discard history through App Functions. " +
                    "If you grant Device library access in Settings (Allow all), that assistant can also list and import videos, audio, and photos already on this device. It does not receive the media files.",
            )
            Text("Open source notices", style = MaterialTheme.typography.titleMedium)
            Text(
                "This software uses libraries from the FFmpeg project under the LGPLv3. " +
                    "FFmpeg is a trademark of Fabrice Bellard. Debug builds ship " +
                    "dev.ffmpegkit-maintained:ffmpeg-kit-full 8.1.7 (FFmpeg 8.1.2, LGPL-3.0). " +
                    "Source for those libraries is available from the upstream projects; see " +
                    "docs/ffmpeg-build.md in the application source for how a Play Store build " +
                    "should be produced from FFmpegKitNext without GPL encoders.",
            )
            Text(
                "The Device (Media3) engine uses AndroidX Media3 Transformer and the platform " +
                    "MediaCodec encoders. That path does not run FFmpeg.",
            )
            Text(
                "HEVC is offered only through the device hardware encoder when present. " +
                    "This app does not ship libx264 or libx265.",
            )
            Text(
                "AndroidX, Kotlin, and Material libraries are used under their respective Apache 2.0 licenses.",
            )
        }
    }
}

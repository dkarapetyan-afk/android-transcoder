package com.androidcompress.app.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.androidcompress.app.container
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.launch

/**
 * Used by the Quick Settings tile to obtain MediaProjection consent and start a recording
 * with the last saved options.
 */
class RecordConsentActivity : ComponentActivity() {
    private var options = RecordOptions()
    private var waitingOverlay = false
    private var leftForOverlay = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            runCatchingLog("RecordConsent", "start after consent") {
                RecordingSession.startAfterConsent(
                    context = this@RecordConsentActivity,
                    container = container(),
                    resultCode = result.resultCode,
                    data = result.data!!,
                    options = options,
                )
            }
            finish()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (Manifest.permission.RECORD_AUDIO in denied && options.audioMode.needsRecordAudioPermission) {
            finish()
            return@registerForActivityResult
        }
        if (Manifest.permission.CAMERA in denied) {
            options = options.copy(facecam = false)
        }
        advance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (container().recording.state.value.active) {
            finish()
            return
        }
        lifecycleScope.launch {
            val stored = container().prefs.recordOptionsJson()
            val prefs = container().prefs.current()
            options = RecordOptions.fromJson(stored).let { parsed ->
                if (stored.isNullOrBlank()) {
                    parsed.copy(
                        audioMode = prefs.lastRecordAudioMode,
                        resolution = prefs.lastRecordResolution,
                    )
                } else {
                    parsed
                }
            }.resolvedForSdk(Build.VERSION.SDK_INT)
            advance()
        }
    }

    override fun onPause() {
        if (waitingOverlay) leftForOverlay = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!waitingOverlay || !leftForOverlay) return
        waitingOverlay = false
        leftForOverlay = false
        if (!canDrawOverlays(this)) {
            options = options.copy(showBubble = false, facecam = false, captureRegion = false)
        }
        advance()
    }

    private fun advance() {
        if (isFinishing) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        if (options.audioMode.needsRecordAudioPermission &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (options.needsCamera &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (options.needsOverlay && !canDrawOverlays(this)) {
            waitingOverlay = true
            requestOverlayPermission(this)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

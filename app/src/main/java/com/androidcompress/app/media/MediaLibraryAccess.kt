package com.androidcompress.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object MediaLibraryAccess {
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return has(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                has(context, Manifest.permission.READ_MEDIA_AUDIO) ||
                has(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    has(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
        return has(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasVideo(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            has(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    has(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else -> has(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAudio(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> has(context, Manifest.permission.READ_MEDIA_AUDIO)
        else -> has(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasImages(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            has(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    has(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else -> has(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun require(context: Context) {
        if (!granted(context)) {
            error(
                "Library access is not granted. Open Settings in Recording Compressor and allow " +
                    "videos, audio, and photos. Choose Allow all, not selected items only.",
            )
        }
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

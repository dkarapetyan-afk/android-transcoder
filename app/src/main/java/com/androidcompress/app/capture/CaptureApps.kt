package com.androidcompress.app.capture

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.androidcompress.app.util.runCatchingLog

data class CaptureApp(
    val packageName: String,
    val label: String,
)

object CaptureApps {
    fun launchers(pm: PackageManager): List<CaptureApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, 0)
        }
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString().ifBlank { pkg }
            CaptureApp(pkg, label)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun uid(pm: PackageManager, packageName: String): Int? {
        if (packageName.isBlank()) return null
        return runCatchingLog(TAG, "uid $packageName") {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).uid
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0).uid
            }
        }.getOrNull()
    }

    fun hasFrontCamera(cameras: CameraManager): Boolean = hasCamera(cameras, CameraCharacteristics.LENS_FACING_FRONT)

    fun hasBackCamera(cameras: CameraManager): Boolean = hasCamera(cameras, CameraCharacteristics.LENS_FACING_BACK)

    fun hasCamera(cameras: CameraManager, facing: Int): Boolean = runCatchingLog(TAG, "hasCamera") {
        cameras.cameraIdList.any { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }.getOrDefault(false)

    private const val TAG = "CaptureApps"
}

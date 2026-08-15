package com.androidcompress.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF006B5F)
private val TealLight = Color(0xFF4FD8C8)
private val SurfaceDark = Color(0xFF101413)
private val SurfaceLight = Color(0xFFF4FBF8)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF4A635E),
    background = SurfaceLight,
    surface = Color.White,
    surfaceVariant = Color(0xFFDDE5E1),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFB1CCC6),
    background = SurfaceDark,
    surface = Color(0xFF1A1F1E),
    surfaceVariant = Color(0xFF3F4946),
)

@Composable
fun RecordingCompressorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

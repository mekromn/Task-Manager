package com.mekromn.taskmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mekromn.taskmanager.data.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF123A70),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF7FD8FF),
    tertiary = Color(0xFFC7A6FF),
    background = Color(0xFF090D14),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF101620),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF192231),
    onSurfaceVariant = Color(0xFFBAC3D4),
    outline = Color(0xFF6E788A),
    error = Color(0xFFFFB4AB)
)

private val AmoledScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002B62),
    primaryContainer = Color(0xFF0A2A53),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF78D9FF),
    tertiary = Color(0xFFC8A7FF),
    background = Color.Black,
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF05070A),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF0C1119),
    onSurfaceVariant = Color(0xFFC5CCDA),
    outline = Color(0xFF697386),
    error = Color(0xFFFFB4AB)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = Color(0xFF00677F),
    tertiary = Color(0xFF6D45A0),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF171C24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C24),
    surfaceVariant = Color(0xFFE8EEF7),
    onSurfaceVariant = Color(0xFF414957),
    outline = Color(0xFF727A89)
)

@Composable
fun TaskManagerTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val colorScheme = when (mode) {
        ThemeMode.AMOLED -> AmoledScheme
        ThemeMode.DARK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context).copy(
                background = DarkScheme.background,
                surface = DarkScheme.surface
            )
        } else DarkScheme
        ThemeMode.LIGHT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(context)
        } else LightScheme
        ThemeMode.SYSTEM -> if (systemDark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context)
            else DarkScheme
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context)
            else LightScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

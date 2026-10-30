package com.example.looma.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A single source of truth for the app's Material 3 Expressive theme.
 *
 * Color priority:
 * 1. System Dynamic Color on Android 12+ (when enabled)
 * 2. Material 3 Expressive fallback scheme for light/dark mode
 *
 * Typography and motion remain consistent regardless of the color source.
 */
@Composable
fun LoomaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LoomaDarkColors
        else -> LoomaLightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = rememberLoomaTypography(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

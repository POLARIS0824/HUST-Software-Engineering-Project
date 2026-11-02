package com.example.looma.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A single source of truth for the app's Material 3 Expressive theme.
 *
 * Color priority:
 * 1. System Dynamic Color on Android 12+ (when enabled)
 * 2. Material 3 Expressive fallback scheme for light/dark mode
 *
 * Typography and motion remain consistent regardless of the color source.
 *
 * The Looma tokens are provided from the same scheme and typography,
 * so screens resolve one set of semantic roles instead of reading the
 * Material theme directly.
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
    val typography = rememberLoomaTypography()
    val colors = remember(colorScheme) { loomaColors(colorScheme) }
    val type = remember(typography) { loomaTypography(typography) }

    CompositionLocalProvider(
        LocalLoomaColors provides colors,
        LocalLoomaTypography provides type,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

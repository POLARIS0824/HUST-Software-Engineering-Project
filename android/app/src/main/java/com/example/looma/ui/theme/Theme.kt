package com.example.looma.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.looma.data.settings.ThemePreset

/**
 * A single source of truth for the app's Material 3 Expressive theme.
 *
 * Color priority:
 * 1. System Dynamic Color on Android 12+ when the user turned it on
 * 2. The selected preset palette, derived from one seed color
 *
 * Typography and motion remain consistent regardless of the color source.
 *
 * The Looma tokens are projected from the same scheme and typography,
 * so the shared surface roles resolve from one place. A one-off editorial
 * moment (onboarding hero, dialog heading) may still name an M3 role
 * directly; what no screen may do is invent a size, weight or family.
 * `verifyTypography` fails the build on exactly that.
 */
@Composable
fun LoomaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    preset: ThemePreset = ThemePreset.Default,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> presetScheme(preset, darkTheme)
    }
    val typography = rememberLoomaTypography()
    val colors = remember(colorScheme) { loomaColors(colorScheme) }
    val type = remember(typography) { loomaTypography(typography) }

    CompositionLocalProvider(
        LocalLoomaColors provides colors,
        LocalLoomaTypography provides type,
        // 根节点也必须给内容色：不带 Surface 的页面里，裸 Text 会退回默认的纯黑，
        // 深色模式下就成了黑底黑字
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

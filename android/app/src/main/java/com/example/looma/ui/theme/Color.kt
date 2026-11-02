package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme

/**
 * Looma has not defined a brand palette yet, so use Material 3 Expressive defaults.
 *
 * These schemes are fallbacks only: on Android 12+ with dynamicColor enabled,
 * LoomaTheme supplies system-derived dynamic colors instead.
 *
 * Once Looma has an intentional brand palette, generate a complete light/dark
 * ColorScheme (for example with Material Theme Builder) instead of overriding
 * unrelated color roles individually.
 */
internal val LoomaLightColors: ColorScheme = expressiveLightColorScheme()
internal val LoomaDarkColors: ColorScheme = darkColorScheme()

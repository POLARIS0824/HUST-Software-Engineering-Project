package edu.hust.justmd.comparison

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp

@Composable
fun comparisonColors(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
}

@Composable
fun comparisonTypography(): Typography {
    val family = FontFamily(Font(R.font.google_sans_flex))
    val hero = FontFamily(Font(R.font.google_sans_flex, FontWeight.Bold, variationSettings = FontVariation.Settings(
        FontVariation.weight(700), FontVariation.width(85f), FontVariation.opticalSizing(45.sp))))
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = hero),
        displayMedium = base.displayMedium.copy(fontFamily = hero),
        displaySmall = base.displaySmall.copy(fontFamily = hero),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

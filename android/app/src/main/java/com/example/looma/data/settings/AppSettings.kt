package com.example.looma.data.settings

import androidx.compose.ui.graphics.Color
import com.example.looma.domain.markdown.ToolbarItem

data class AppSettings(
    val dynamicColor: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.Default,
    val keepEmptyNotes: Boolean = false,
    val showInlineTitle: Boolean = true,
    val toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
    val imageUploadTarget: ImageUploadTarget = ImageUploadTarget.LocalAttachment,
    val imageBed: S3ImageBedConfig = S3ImageBedConfig(),
    val isLoaded: Boolean = false,
)

enum class ThemePreset(val seed: Color?) {
    Default(null),
    Indigo(Color(0xFF4155C6)),
    Teal(Color(0xFF00696B)),
    Rose(Color(0xFF9C4146)),
    Amber(Color(0xFF7C5800)),
}

enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Chinese("zh"),
}

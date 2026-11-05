package com.example.looma.data.settings

import androidx.compose.ui.graphics.Color
import com.example.looma.domain.markdown.ToolbarItem

/** 应用级偏好：主题与新建笔记的行为，和笔记库配置分开存 */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.Default,
    /** 新建后没有写任何内容就离开时，是否保留这个空笔记 */
    val keepEmptyNotes: Boolean = false,
    val toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
    /** 是否已经读过一次磁盘，界面上避免用默认值闪一帧 */
    val isLoaded: Boolean = false,
)

/**
 * 预设配色。每套只描述一个种子色，其余色调由 [com.example.looma.ui.theme.presetScheme] 推导，
 * 这样新增一套主题只需要加一个种子色。
 */
enum class ThemePreset(val seed: Color?) {
    /** Material 3 Expressive 基线配色 */
    Default(null),
    Indigo(Color(0xFF4155C6)),
    Teal(Color(0xFF00696B)),
    Rose(Color(0xFF9C4146)),
    Amber(Color(0xFF7C5800)),
}

/** 按应用语言，改的是 AppCompat 的应用级 locale，不跟随系统 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Chinese("zh"),
}

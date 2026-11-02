package com.example.looma.data.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用级语言，走 AppCompat 的应用 locale。
 *
 * 这是语言的唯一来源：系统设置里的「按应用设置语言」改的也是它，
 * 所以设置页不需要再存一份，读回来就是当前值。
 */
object AppLocale {

    fun current(): AppLanguage {
        val language = AppCompatDelegate.getApplicationLocales()[0]?.language
        return when {
            language.isNullOrBlank() -> AppLanguage.System
            language == "zh" -> AppLanguage.Chinese
            language == "en" -> AppLanguage.English
            else -> AppLanguage.System
        }
    }

    fun apply(language: AppLanguage) {
        val tag = language.tag
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
    }
}

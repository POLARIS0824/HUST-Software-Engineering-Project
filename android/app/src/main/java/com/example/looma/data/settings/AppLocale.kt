package com.example.looma.data.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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

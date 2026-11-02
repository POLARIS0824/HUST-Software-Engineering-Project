package com.example.looma.ui.format

import android.content.Context
import android.text.format.Formatter
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/** 文件大小与时间都按系统语言格式化，不在资源里写死单位 */
fun formatFileSize(context: Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes)

fun formatDateTime(instant: Instant, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .format(Date.from(instant))

package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File

internal fun resolveCover(root: File, noteFile: File, reference: String?): String? =
    (resolveImageSource(root.absolutePath, noteFile.absolutePath, reference) as? ImageSource.Local)?.path

package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File

/**
 * 把 Markdown 里的图片引用解析成绝对路径，供卡片渲染封面。
 *
 * 规则与正文图片同一份 [resolveImageSource]：只接受 Vault 内真实存在的图片，
 * 这里再去掉远程地址，因为封面只能来自本地文件。
 */
internal fun resolveCover(root: File, noteFile: File, reference: String?): String? =
    (resolveImageSource(root.absolutePath, noteFile.absolutePath, reference) as? ImageSource.Local)?.path

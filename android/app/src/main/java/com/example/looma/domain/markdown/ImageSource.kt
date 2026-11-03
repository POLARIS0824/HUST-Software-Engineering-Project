package com.example.looma.domain.markdown

/** 正文里一条图片引用解析之后的结果 */
sealed interface ImageSource {
    /** Vault 内的本地文件，[path] 是绝对路径 */
    data class Local(val path: String) : ImageSource

    /** 图床或其他在线地址 */
    data class Remote(val url: String) : ImageSource
}

/** 解析图片引用需要的两个坐标：库根和当前笔记的绝对路径 */
data class ImageContext(
    val vaultRoot: String,
    val noteFile: String,
)

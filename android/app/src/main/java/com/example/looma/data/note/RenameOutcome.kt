package com.example.looma.data.note

import com.example.looma.domain.model.Note

/** 改名或移动之后：笔记本身，以及有多少篇引用方的正文被改写过 / 没改写成功 */
data class RenameOutcome(
    val note: Note,
    val linksUpdated: Int,
    val linksFailed: Int,
)

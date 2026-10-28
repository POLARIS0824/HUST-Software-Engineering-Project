package edu.hust.justmd.prototype

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Style(val label: String) {
    Obsidian("A · 深色工作台"), Paper("B · 纸张笔记"), Expressive("C · 灵感手册")
}

data class Note(val id: String, val title: String, val folder: String, val excerpt: String,
                val markdown: String, val updated: String, val pinned: Boolean = false) {
    val tags get() = Regex("(?<!\\S)#([\\p{L}\\p{N}_/]+)").findAll(markdown).map { it.groupValues[1] }.toList()
}

val fixtureNotes = listOf(
    Note("tcp", "TCP 拥塞控制", "计算机网络", "让发送速度，跟上网络的节奏。慢启动、拥塞避免与一条增长曲线。", """
        # TCP 拥塞控制

        让发送速度，跟上网络的节奏。

        ## 从慢启动开始

        连接建立后，拥塞窗口 **cwnd** 从较小的值开始，随 ACK 到达逐步增长。

        > 核心问题不是“能发多少”，而是“网络还能承受多少”。

        - **慢启动**：探索可用带宽
        - **拥塞避免**：越接近阈值，越谨慎
        - **快速恢复**：从丢包中恢复，而非重新开始

        ## 知识之间的连接

        与 [[TCP 三次握手]] 一起理解连接的生命周期。延伸阅读 [[网络中的反馈机制]]。

        #计算机网络 #期末复习
    """.trimIndent() + "\n", "10:42", true),
    Note("inbox", "把想法先记下来", "随手记", "不急着分类。那些还没有名字的想法，也值得留一个位置。", "# 把想法先记下来\n\n不急着分类。那些还没有名字的想法，也值得留一个位置。\n\n- [ ] 做一个更安静的笔记工具\n- [ ] 整理本周读书摘录\n\n#灵感\n", "09:18", true),
    Note("handshake", "TCP 三次握手", "计算机网络", "SYN → SYN-ACK → ACK。确认彼此可以接收，也可以发送。", "# TCP 三次握手\n\nSYN → SYN-ACK → ACK。\n\n连接建立后，下一步是 [[TCP 拥塞控制]]。\n\n#计算机网络\n", "昨天"),
    Note("local", "本地优先的意义", "产品思考", "文件属于你。离线可写，随时可带走，工具只是通往内容的入口。", "# 本地优先的意义\n\n文件属于你。离线可写，随时可带走。\n\n工具只是通往内容的入口。\n\n#产品思考 #灵感\n", "昨天"),
    Note("reading", "十月的阅读碎片", "阅读", "好的笔记，不是把一本书变短，而是让一个问题变得清楚。", "# 十月的阅读碎片\n\n好的笔记，不是把一本书变短，而是让一个问题变得清楚。\n\n读书时也常想起 [[本地优先的意义]]。\n\n#阅读\n", "周一"),
    Note("review", "期末复习路线", "学习计划", "先画出知识的联系，再回到每一个细节。今天从网络开始。", "# 期末复习路线\n\n- [ ] [[TCP 拥塞控制]]\n- [ ] [[TCP 三次握手]]\n- [ ] 画一张属于自己的知识地图\n\n#期末复习\n", "周日")
)

class ResearchSession(initialStyle: Style, initialNote: String?) {
    init { require(fixtureNotes.map { it.id }.let { ids -> ids.none(String::isBlank) && ids.distinct().size == ids.size }) }
    var style by mutableStateOf(initialStyle)
    var noteId by mutableStateOf(initialNote)
    var query by mutableStateOf("")
    var onlyPinned by mutableStateOf(false)
    var destination by mutableStateOf(0)
    var drafts by mutableStateOf(mapOf<String, String>())
    var notes by mutableStateOf(fixtureNotes)
    val selected get() = notes.find { it.id == noteId }
    val visible get() = notes.filter {
        (!onlyPinned || it.pinned) && (query.isBlank() ||
            (it.title + it.markdown).contains(query, ignoreCase = true))
    }
    fun updateDraft(id: String, body: String) { drafts = drafts + (id to body) }
    fun newNote() {
        val note = Note("draft-${System.nanoTime()}", "未命名笔记", "随手记", "", "# 未命名笔记\n\n", "现在")
        notes = listOf(note) + notes
        noteId = note.id
    }
}

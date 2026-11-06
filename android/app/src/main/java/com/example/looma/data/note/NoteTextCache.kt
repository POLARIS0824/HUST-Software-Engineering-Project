package com.example.looma.data.note

import com.example.looma.domain.model.NoteId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class NoteTextCache {

    private data class Entry(val modifiedAt: Instant, val text: String)

    private val entries = ConcurrentHashMap<NoteId, Entry>()

    fun get(id: NoteId, modifiedAt: Instant): String? =
        entries[id]?.takeIf { it.modifiedAt == modifiedAt }?.text

    fun put(id: NoteId, modifiedAt: Instant, text: String) {
        entries[id] = Entry(modifiedAt, text)
    }

    fun move(from: NoteId, to: NoteId) {
        entries.remove(from)?.let { entries[to] = it }
    }

    fun remove(id: NoteId) {
        entries.remove(id)
    }
}

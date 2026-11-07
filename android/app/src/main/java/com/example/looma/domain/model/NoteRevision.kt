package com.example.looma.domain.model

import java.security.MessageDigest

sealed interface NoteRevision {
    data class Known(val sha256: String) : NoteRevision

    data object Unknown : NoteRevision

    companion object {
        fun of(bytes: ByteArray): NoteRevision = Known(
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }
}

sealed interface NoteWriteMode {
    data class Checked(val revision: NoteRevision) : NoteWriteMode

    data class Overwrite(val revision: NoteRevision) : NoteWriteMode
}

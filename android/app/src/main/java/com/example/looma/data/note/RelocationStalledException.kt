package com.example.looma.data.note

import com.example.looma.domain.model.StalledRelocation
import java.io.IOException

class RelocationStalledException(
    val stalled: StalledRelocation,
    cause: Throwable? = null,
) : IOException("relocation stalled: ${stalled.journalId}", cause)

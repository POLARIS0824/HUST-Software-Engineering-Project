# Selected shape

Use candidate B, two flavor APKs with shared Compose chrome and native reader. The independent gpt-6-sol judge scored B 10 and A 8 against the rubric. Parent agrees that physical native packaging exclusion is useful and runtime engine switching introduces irrelevant history-transfer behavior.

Graft A's serialized toolbar, note and Edit/Read transitions. A failed acknowledgement keeps the current editor visible and shows an error. Engine-private per-note state is authoritative; observed DraftSnapshot is read-only. The reader receives an acknowledged source and cannot write it back.

The implementation contract adds `ready`, a typed selection/insert command for reproducible backend probes, an Android Context argument for the CM host, and `close()` for activity-owned resources. These are parent-accepted implementation details of the same boundaries. The CM view stays mounted behind native library and read surfaces. Native uses one retained TextFieldState per note. No history transfer between APKs or process-death persistence is promised.

The inspected renderer 0.39.2 supports the needed API and its Kotlin 2.3.0 metadata will be checked with the existing pinned build. Do not infer compatibility from the researcher's hypothetical Kotlin 2.2.21 shell. The actual shell pins Compose/serialization plugins 2.3.20 and uses AGP built-in Kotlin.

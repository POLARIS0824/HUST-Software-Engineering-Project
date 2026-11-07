package com.example.looma.data.vault

import com.example.looma.domain.model.Vault
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 统一写入入口 */
internal object VaultWriteCoordinator {
    private val gates = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> write(vault: Vault, block: suspend () -> T): T {
        val gate = gates.computeIfAbsent(File(vault.rootPath).canonicalPath) { Mutex() }
        gate.lock()
        try {
            return withContext(Dispatchers.IO + NonCancellable) { block() }
        } finally {
            gate.unlock()
        }
    }
}

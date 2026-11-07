package com.example.looma.data.vault

import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultWriteCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test(timeout = 5_000)
    fun `different vault instances share the canonical root gate`() = runBlocking {
        val root = temporaryFolder.newFolder("vault")
        val first = vault(root, "first")
        val alias = vault(File(root, "."), "alias")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val holder = launch {
            VaultWriteCoordinator.write(first) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            VaultWriteCoordinator.write(alias) { secondEntered.complete(Unit) }
        }
        try {
            assertFalse(secondEntered.isCompleted)
        } finally {
            release.complete(Unit)
        }
        holder.join()
        waiter.join()
        assertTrue(secondEntered.isCompleted)
    }

    @Test(timeout = 5_000)
    fun `independent roots do not block each other`() = runBlocking {
        val first = vault(temporaryFolder.newFolder("first"), "same-id")
        val second = vault(temporaryFolder.newFolder("second"), "same-id")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            VaultWriteCoordinator.write(first) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            assertEquals("second", VaultWriteCoordinator.write(second) { second.name })
        } finally {
            release.complete(Unit)
        }
        holder.join()
    }

    @Test(timeout = 5_000)
    fun `waiting for a gate remains cancellable`() = runBlocking {
        val vault = vault(temporaryFolder.newFolder("vault"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()
        val holder = launch {
            VaultWriteCoordinator.write(vault) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            VaultWriteCoordinator.write(vault) { waiterEntered.complete(Unit) }
        }
        try {
            waiter.cancelAndJoin()
            assertFalse(waiterEntered.isCompleted)
        } finally {
            release.complete(Unit)
        }
        holder.join()
        assertEquals("available", VaultWriteCoordinator.write(vault) { "available" })
    }

    @Test(timeout = 5_000)
    fun `cancellation after acquisition does not interrupt the write or release its gate early`() = runBlocking {
        val vault = vault(temporaryFolder.newFolder("vault"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()
        val holder = launch {
            VaultWriteCoordinator.write(vault) {
                entered.complete(Unit)
                release.await()
                currentCoroutineContext().ensureActive()
                finished.complete(Unit)
            }
        }
        entered.await()
        holder.cancel()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            VaultWriteCoordinator.write(vault) { waiterEntered.complete(Unit) }
        }
        try {
            assertFalse(finished.isCompleted)
            assertFalse(waiterEntered.isCompleted)
        } finally {
            release.complete(Unit)
        }
        holder.join()
        waiter.join()
        assertTrue(finished.isCompleted)
        assertTrue(waiterEntered.isCompleted)
    }

    @Test(timeout = 5_000)
    fun `failed writes release the gate and preserve their exception`() = runBlocking {
        val vault = vault(temporaryFolder.newFolder("vault"))
        val failure = IOException("failed")

        val result = runCatching { VaultWriteCoordinator.write(vault) { throw failure } }

        assertEquals(IOException::class.java, result.exceptionOrNull()?.javaClass)
        assertEquals("failed", result.exceptionOrNull()?.message)
        assertEquals("available", VaultWriteCoordinator.write(vault) { "available" })
    }

    private fun vault(root: File, id: String = "vault") = Vault(VaultId(id), root.name, root.absolutePath)
}

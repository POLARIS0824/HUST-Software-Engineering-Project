package com.example.looma.domain.model

/** Empty relativePath represents the root of the Vault. */
@JvmInline
value class FolderPath(val relativePath: String) {
    companion object {
        val Root = FolderPath("")
    }
}

data class VaultFolder(
    val vaultId: VaultId,
    val path: FolderPath,
    val name: String,
)

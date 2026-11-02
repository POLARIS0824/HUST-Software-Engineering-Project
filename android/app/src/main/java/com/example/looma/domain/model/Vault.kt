package com.example.looma.domain.model

@JvmInline
value class VaultId(val value: String)

data class Vault(
    val id: VaultId,
    val name: String,
    val rootPath: String,
) {
    val displayPath: String
        get() = rootPath
}

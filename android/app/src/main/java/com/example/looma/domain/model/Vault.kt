package com.example.looma.domain.model

/**
 * @JvmInline 是 Kotlin 中的一个注解，用来声明 内联值类（Value Class）
 * 主要作用是：给一个值包装上独立的类型，同时尽量避免创建额外对象
 */
@JvmInline
value class VaultId(val value: String)

data class Vault(
    val id: VaultId,
    val name: String,
    val displayPath: String
)
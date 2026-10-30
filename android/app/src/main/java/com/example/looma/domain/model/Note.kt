package com.example.looma.domain.model

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
)
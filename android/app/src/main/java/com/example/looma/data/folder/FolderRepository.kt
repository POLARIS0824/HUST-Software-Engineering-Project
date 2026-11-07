package com.example.looma.data.folder

import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultFolder

class FolderRepository(private val notes: NoteRepository) {
    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder = notes.createFolder(parent, name)

    suspend fun renameFolder(path: FolderPath, newName: String): VaultFolder = notes.renameFolder(path, newName)

    suspend fun moveFolder(path: FolderPath, destination: FolderPath): VaultFolder = notes.moveFolder(path, destination)

    suspend fun deleteFolder(path: FolderPath): List<String> = notes.deleteFolder(path)
}

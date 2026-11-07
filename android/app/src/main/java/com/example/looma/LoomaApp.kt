package com.example.looma

import android.app.Application
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.size.Precision

class LoomaApp : Application(), SingletonImageLoader.Factory {
    val vaultRepository by lazy { VaultRepository(this) }
    private val indexDatabase by lazy { NoteIndexDatabase.get(this) }
    val noteRepository by lazy { NoteRepository(vaultRepository, RoomNoteIndex(indexDatabase)) }
    val searchHistoryRepository by lazy {
        SearchHistoryRepository(vaultRepository, indexDatabase.searchHistoryDao())
    }
    val folderRepository by lazy { FolderRepository(noteRepository) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val imageUploader by lazy { ImageUploader(this, vaultRepository) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .precision(Precision.INEXACT)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .build()
            }
            .build()
}

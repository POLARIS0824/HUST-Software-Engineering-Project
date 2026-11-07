package com.example.looma.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.looma.data.search.SearchHistoryDao

@Database(
    entities = [
        IndexedNoteEntity::class,
        NoteTagEntity::class,
        NoteLinkEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class NoteIndexDatabase : RoomDatabase() {

    abstract fun noteIndexDao(): NoteIndexDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        private const val NAME = "looma_index.db"

        @Volatile
        private var instance: NoteIndexDatabase? = null

        fun get(context: Context): NoteIndexDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteIndexDatabase::class.java,
                NAME,
            )
                .build()
                .also { instance = it }
        }
    }
}

package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatSession::class, ChatMessage::class, SharedMemory::class],
    version = 1,
    exportSchema = false
)
abstract class DoxonDatabase : RoomDatabase() {
    abstract fun doxonDao(): DoxonDao

    companion object {
        @Volatile
        private var INSTANCE: DoxonDatabase? = null

        fun getDatabase(context: Context): DoxonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DoxonDatabase::class.java,
                    "doxon_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

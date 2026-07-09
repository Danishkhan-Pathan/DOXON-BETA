package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DoxonDao {
    @Query("SELECT * FROM chat_sessions ORDER BY lastUpdated DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE isIncognito = :isIncognito ORDER BY lastUpdated DESC")
    fun getSessionsByIncognito(isIncognito: Boolean): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySessionId(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionDirect(sessionId: Long): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("SELECT * FROM chat_messages WHERE isPending = 1 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<ChatMessage>

    @Query("UPDATE chat_messages SET isPending = 0, text = :newText WHERE id = :messageId")
    suspend fun markMessageSent(messageId: Long, newText: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfter(sessionId: Long, timestamp: Long)

    // Shared memories
    @Query("SELECT * FROM shared_memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<SharedMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: SharedMemory): Long

    @Query("DELETE FROM shared_memories WHERE `key` = :key")
    suspend fun deleteMemoryByKey(key: String)

    // For deleting expired 24h incognito sessions
    @Query("DELETE FROM chat_sessions WHERE isIncognito = 1 AND incognitoExpiresAt < :currentTime")
    suspend fun deleteExpiredIncognitoSessions(currentTime: Long)
}

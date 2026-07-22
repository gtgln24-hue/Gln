package com.sshvpn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sshvpn.model.LogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 500")
    fun observeAll(): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 500")
    fun observeBySession(sessionId: String): Flow<List<LogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LogEntry)

    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun deleteOldEntries(keep: Int)

    @Query("DELETE FROM log_entries")
    suspend fun clearAll()
}

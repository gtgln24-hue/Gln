package com.sshvpn.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sshvpn.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM connection_profiles ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<ConnectionProfile>>

    @Query("SELECT * FROM connection_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ConnectionProfile?

    @Query("SELECT * FROM connection_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ConnectionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ConnectionProfile): Long

    @Update
    suspend fun update(profile: ConnectionProfile)

    @Delete
    suspend fun delete(profile: ConnectionProfile)

    @Query("UPDATE connection_profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE connection_profiles SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)

    @Query("UPDATE connection_profiles SET lastUsedAt = :time WHERE id = :id")
    suspend fun updateLastUsed(id: Long, time: Long)
}

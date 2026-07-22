package com.sshvpn.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sshvpn.model.ConnectionProfile
import com.sshvpn.model.LogEntry

@Database(
    entities = [ConnectionProfile::class, LogEntry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun logDao(): LogDao

    companion object {
        private const val DB_NAME = "ssh_vpn.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}

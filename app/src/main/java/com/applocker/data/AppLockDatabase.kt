package com.applocker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LockedApp::class], version = 1, exportSchema = false)
abstract class AppLockDatabase : RoomDatabase() {

    abstract fun lockedAppDao(): LockedAppDao

    companion object {
        @Volatile
        private var INSTANCE: AppLockDatabase? = null

        fun getInstance(context: Context): AppLockDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppLockDatabase::class.java,
                    "app_lock.db"
                ).build().also { INSTANCE = it }
            }
    }
}

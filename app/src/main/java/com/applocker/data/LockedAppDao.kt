package com.applocker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {

    @Query("SELECT * FROM locked_apps ORDER BY appName ASC")
    fun getAllFlow(): Flow<List<LockedApp>>

    @Query("SELECT * FROM locked_apps")
    suspend fun getAll(): List<LockedApp>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :pkg AND isEnabled = 1)")
    suspend fun isLocked(pkg: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: LockedApp)

    @Delete
    suspend fun delete(app: LockedApp)

    @Query("DELETE FROM locked_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("UPDATE locked_apps SET isEnabled = :enabled WHERE packageName = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)
}

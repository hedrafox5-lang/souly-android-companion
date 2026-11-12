package com.souly.parental.shield.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OfflineTelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: OfflineTelemetryEntity)

    @Query("SELECT * FROM offline_telemetry_queue ORDER BY id ASC LIMIT 30")
    suspend fun getPendingItems(): List<OfflineTelemetryEntity>

    @Query("DELETE FROM offline_telemetry_queue WHERE id = :id")
    suspend fun deleteById(id: Long)
}

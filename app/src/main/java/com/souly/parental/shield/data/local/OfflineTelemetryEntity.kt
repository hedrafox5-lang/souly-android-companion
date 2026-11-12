package com.souly.parental.shield.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_telemetry_queue")
data class OfflineTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payloadType: String,
    val payloadJson: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

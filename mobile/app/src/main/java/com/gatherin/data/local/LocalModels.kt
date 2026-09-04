package com.gatherin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_scans")
data class PendingScanEntity(
    @PrimaryKey
    val token: String,
    val stationId: String,
    val clientScannedAt: String,
    val queuedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "synced_cache")
data class SyncedScanEntity(
    @PrimaryKey
    val token: String,
    val attendeeName: String?,
    val status: String, // success, duplicate, expired, invalid
    val syncedAt: Long = System.currentTimeMillis()
)

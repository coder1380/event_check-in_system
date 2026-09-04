package com.gatherin.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(scan: PendingScanEntity)

    @Query("SELECT * FROM pending_scans ORDER BY queuedAt ASC")
    fun getPendingScans(): Flow<List<PendingScanEntity>>

    @Query("SELECT * FROM pending_scans ORDER BY queuedAt ASC")
    suspend fun getPendingScansList(): List<PendingScanEntity>

    @Delete
    suspend fun deletePending(scan: PendingScanEntity)

    @Query("SELECT COUNT(*) FROM pending_scans")
    suspend fun getPendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSynced(scan: SyncedScanEntity)

    @Query("SELECT * FROM synced_cache WHERE token = :token LIMIT 1")
    suspend fun getSynced(token: String): SyncedScanEntity?

    @Query("SELECT * FROM synced_cache ORDER BY syncedAt DESC")
    fun getSyncedScans(): Flow<List<SyncedScanEntity>>

    @Query("DELETE FROM synced_cache")
    suspend fun clearHistory()
}

@Database(entities = [PendingScanEntity::class, SyncedScanEntity::class], version = 1, exportSchema = false)
abstract class ScannerDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: ScannerDatabase? = null

        fun getDatabase(context: Context): ScannerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScannerDatabase::class.java,
                    "scanner_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

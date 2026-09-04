package com.gatherin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gatherin.data.CheckinRequest
import com.gatherin.data.RetrofitClient
import com.gatherin.data.SyncBatchRequest
import com.gatherin.data.local.ScannerDatabase
import com.gatherin.data.local.SyncedScanEntity

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = ScannerDatabase.getDatabase(applicationContext)
        val dao = db.scanDao()
        val api = RetrofitClient.api

        val pending = dao.getPendingScansList()
        if (pending.isEmpty()) return Result.success()

        var allSuccessful = true

        for (scan in pending) {
            try {
                val res = api.syncBatch(SyncBatchRequest(
                    stationId = scan.stationId,
                    scans = listOf(CheckinRequest(scan.token, scan.stationId, scan.clientScannedAt))
                ))
                if (res.isSuccessful) {
                    val syncResult = res.body()?.results?.firstOrNull()
                    if (syncResult != null) {
                        dao.insertSynced(SyncedScanEntity(
                            token = scan.token,
                            attendeeName = syncResult.checkin?.attendeeName,
                            status = when (syncResult.status) {
                                "accepted" -> "success"
                                "rejected_duplicate" -> "duplicate"
                                "rejected_expired" -> "expired"
                                else -> "invalid"
                            }
                        ))
                    }
                    dao.deletePending(scan)
                } else {
                    allSuccessful = false
                }
            } catch (e: Exception) {
                allSuccessful = false
                break
            }
        }

        return if (allSuccessful) Result.success() else Result.retry()
    }
}

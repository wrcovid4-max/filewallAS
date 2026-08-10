package com.filewall.data.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filewall.FileWallApp
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unattended daily upload of the encrypted archive to Drive.
 *
 * Runs on the same `.fwvault` path as the manual button — there is no second format and no
 * second code path to keep honest. The archive key is the managed key in the account's Drive
 * appDataFolder, so the worker holds no secret of its own; it only needs the signed-in
 * account and a network. Transient failures retry; a signed-out account fails the run.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val container = (applicationContext as FileWallApp).container

        val staging = File(applicationContext.cacheDir, STAGING_NAME)
        return try {
            // Sign-in-only backup: the key lives in the account's Drive appDataFolder, so the
            // worker needs no stored secret — just the signed-in account and a network.
            val passphrase = container.drive.managedPassphrase()
            try {
                container.archive.exportTo(staging, passphrase)
            } finally {
                passphrase.fill(' ')
            }
            container.drive.upload(staging)
            ListenableWorker.Result.success()
        } catch (error: Exception) {
            Log.w(TAG, "Scheduled backup failed (attempt $runAttemptCount)", error)
            // Transient things — no network at 3am, a token needing refresh — deserve another
            // go. Past that, stop burning battery on it and wait for the next daily window.
            if (runAttemptCount < MAX_ATTEMPTS) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        } finally {
            staging.delete()
        }
    }

    companion object {
        private const val TAG = "FileWallBackup"
        private const val STAGING_NAME = "auto-backup.fwvault"
        private const val MAX_ATTEMPTS = 3
        private const val WORK_NAME = "filewall-drive-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // A whole vault over a metered connection is not a decision to make
                        // on someone's behalf.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

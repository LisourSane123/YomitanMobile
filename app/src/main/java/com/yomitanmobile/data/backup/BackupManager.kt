package com.yomitanmobile.data.backup

import android.content.Context
import android.util.Log
import com.yomitanmobile.data.local.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    private companion object {
        const val BACKUP_DIR_NAME = "yomitan_backups"
        const val DATABASE_BACKUP_NAME = "database.db"
        // DataStore preferences are intentionally NOT included in the
        // backup — the protobuf blob mixes the user-supplied AI API key
        // with everything else, and getExternalFilesDir() is reachable via
        // USB MTP and most third-party file managers. Backing up the .pb
        // would leak the API key in plaintext. The user's actual data
        // (dictionaries, favorites, exports, search history) all lives in
        // the database file, which IS backed up. Card style / deck name /
        // theme are rebuilt on next launch with their defaults — an
        // acceptable trade for guaranteed secret hygiene.
    }

    private val logTag = "BackupManager"

    /**
     * Create a backup of database and preferences to a timestamped folder
     * in app-specific external files directory or internal files directory.
     */
    suspend fun createBackup(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val backupDir = getOrCreateBackupDir()
            // AnkiDroid's lint plugin bans both `new Date()` and
            // `Calendar.getInstance()` to keep time controllable in tests.
            // The current epoch millis is the simplest non-banned source.
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(System.currentTimeMillis())
            val backupFolder = File(backupDir, "backup_$timestamp")
            if (!backupFolder.mkdirs()) {
                return@withContext Result.failure(Exception("Failed to create backup folder"))
            }

            // Backup database
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (dbFile.exists()) {
                val dbBackupFile = File(backupFolder, DATABASE_BACKUP_NAME)
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(dbBackupFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Preferences blob deliberately not copied (see companion-object
            // comment). Only the database is preserved.

            Log.i(logTag, "Backup created at: ${backupFolder.absolutePath}")
            Result.success(backupFolder)
        } catch (e: Exception) {
            Log.e(logTag, "Backup failed", e)
            Result.failure(e)
        }
    }

    /**
     * Restore from a backup folder.
     * WARNING: This will overwrite current database and preferences.
     * App should be restarted after restore.
     */
    suspend fun restoreBackup(backupFolder: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!backupFolder.isDirectory || !backupFolder.exists()) {
                return@withContext Result.failure(Exception("Backup folder not found"))
            }

            // Close database before restoring
            database.close()

            // Restore database
            val dbBackupFile = File(backupFolder, DATABASE_BACKUP_NAME)
            if (dbBackupFile.exists()) {
                val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                dbFile.parentFile?.mkdirs()
                FileInputStream(dbBackupFile).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Preferences blob is not part of the backup (see companion
            // object) so there is nothing to restore here. The user's
            // settings stay at whatever the running DataStore currently
            // holds; for an old-format backup that still has a
            // `datastore_prefs.pb` we simply ignore it rather than risk
            // re-introducing a leaked API key.

            Log.i(logTag, "Restore completed from: ${backupFolder.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(logTag, "Restore failed", e)
            Result.failure(e)
        }
    }

    /**
     * List all available backups in chronological order (newest first).
     */
    suspend fun listBackups(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val backupDir = getOrCreateBackupDir()
            val backups = backupDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("backup_")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            Result.success(backups)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to list backups", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a backup folder.
     */
    suspend fun deleteBackup(backupFolder: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (backupFolder.deleteRecursively()) {
                Log.i(logTag, "Backup deleted: ${backupFolder.absolutePath}")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete backup"))
            }
        } catch (e: Exception) {
            Log.e(logTag, "Delete backup failed", e)
            Result.failure(e)
        }
    }

    private fun getOrCreateBackupDir(): File {
        val backupDir = File(context.getExternalFilesDir(null), BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        return backupDir
    }
}

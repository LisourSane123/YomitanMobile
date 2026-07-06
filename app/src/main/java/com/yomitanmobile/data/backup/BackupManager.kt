package com.yomitanmobile.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
        // Settings are exported to a plain-text JSON file (not the raw
        // DataStore .pb) so we can whitelist what leaves the sandbox. The
        // AI API key (MainActivity.CARD_AI_API_KEY) is ALWAYS excluded on
        // both export and import — getExternalFilesDir() is reachable via
        // USB MTP / file managers, so the secret must never land in a
        // backup. Everything else (card style, deck name, theme, section
        // order, frequency display, daily goal…) is safe to carry over and
        // is restored on the next launch. The user opts in per-backup via
        // the "include settings" toggle.
        const val SETTINGS_BACKUP_NAME = "settings.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val logTag = "BackupManager"

    /**
     * Create a backup of database and preferences to a timestamped folder
     * in app-specific external files directory or internal files directory.
     */
    suspend fun createBackup(includeSettings: Boolean = true): Result<File> = withContext(Dispatchers.IO) {
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

            // Backup database. Room runs SQLite in WAL mode, so recent writes
            // live in the -wal sidecar, not the main .db file — checkpoint
            // first so the .db alone is a complete, consistent snapshot.
            checkpointWal()

            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (dbFile.exists()) {
                val dbBackupFile = File(backupFolder, DATABASE_BACKUP_NAME)
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(dbBackupFile).use { output ->
                        input.copyTo(output)
                    }
                }
                // Belt and braces: if the checkpoint failed (locked reader,
                // I/O error) the -wal still holds unmerged writes. Copy the
                // sidecars too so restore can replay them instead of losing
                // that data. After a successful TRUNCATE checkpoint these
                // files are empty or absent, so this usually copies nothing.
                copySidecarsIfPresent(dbFile, backupFolder)
            }

            // Optionally export the whitelisted settings (AI key excluded).
            if (includeSettings) {
                try {
                    writePreferencesBackup(File(backupFolder, SETTINGS_BACKUP_NAME))
                } catch (e: Exception) {
                    // A settings-export failure must not sink the whole backup —
                    // the database is the load-bearing part.
                    Log.w(logTag, "Settings export failed; database backup kept", e)
                }
            }

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
                // Drop the CURRENT database's WAL sidecars before overwriting
                // the .db. SQLite pairs a .db with whatever -wal/-shm sit next
                // to it — leaving the old ones behind would replay the old
                // database's WAL frames into the restored file and corrupt it.
                deleteSidecars(dbFile)
                FileInputStream(dbBackupFile).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                // If the backup carried its own sidecars (checkpoint failed at
                // backup time), restore them so those writes aren't lost.
                restoreSidecarsIfPresent(dbFile, backupFolder)
            }

            // Restore whitelisted settings when the backup includes them.
            // The AI key is skipped again on the import side (defence in
            // depth against a hand-edited settings.json). Older backups
            // without a settings.json simply keep the current settings.
            val settingsFile = File(backupFolder, SETTINGS_BACKUP_NAME)
            if (settingsFile.exists()) {
                try {
                    restorePreferencesBackup(settingsFile)
                } catch (e: Exception) {
                    Log.w(logTag, "Settings restore failed; database restored anyway", e)
                }
            }

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

    /**
     * Serialize every DataStore preference (except the AI API key) to a typed
     * JSON map so it can be restored on another install / device. Each entry is
     * `{ "t": <type>, "v": <value> }` so the exact preference key type is
     * reconstructable on import.
     */
    private suspend fun writePreferencesBackup(target: File) {
        val prefs = context.dataStore.data.first()
        val root = buildJsonObject {
            for ((key, value) in prefs.asMap()) {
                // Never export the AI API key — the backup dir is world-readable.
                if (key.name == MainActivity.CARD_AI_API_KEY.name) continue
                val entry = preferenceEntry(value) ?: continue
                put(key.name, entry)
            }
        }
        target.writeText(json.encodeToString(JsonObject.serializer(), root))
    }

    private fun preferenceEntry(value: Any?): JsonObject? = when (value) {
        is Boolean -> buildJsonObject { put("t", "bool"); put("v", value) }
        is Int -> buildJsonObject { put("t", "int"); put("v", value) }
        is Long -> buildJsonObject { put("t", "long"); put("v", value) }
        is Float -> buildJsonObject { put("t", "float"); put("v", value) }
        is Double -> buildJsonObject { put("t", "double"); put("v", value) }
        is String -> buildJsonObject { put("t", "string"); put("v", value) }
        is Set<*> -> buildJsonObject {
            put("t", "stringset")
            put("v", JsonArray(value.map { JsonPrimitive(it.toString()) }))
        }
        else -> null
    }

    /**
     * Apply a settings.json produced by [writePreferencesBackup] back into the
     * DataStore. Malformed entries are skipped individually and the AI key is
     * refused again here as defence in depth.
     */
    private suspend fun restorePreferencesBackup(source: File) {
        val root = json.parseToJsonElement(source.readText()).jsonObject
        context.dataStore.edit { prefs ->
            for ((name, element) in root) {
                if (name == MainActivity.CARD_AI_API_KEY.name) continue
                val obj = element as? JsonObject ?: continue
                val type = obj["t"]?.jsonPrimitive?.contentOrNull ?: continue
                val v = obj["v"] ?: continue
                try {
                    when (type) {
                        "bool" -> prefs[booleanPreferencesKey(name)] = v.jsonPrimitive.boolean
                        "int" -> prefs[intPreferencesKey(name)] = v.jsonPrimitive.int
                        "long" -> prefs[longPreferencesKey(name)] = v.jsonPrimitive.long
                        "float" -> prefs[floatPreferencesKey(name)] = v.jsonPrimitive.float
                        "double" -> prefs[doublePreferencesKey(name)] = v.jsonPrimitive.double
                        "string" -> prefs[stringPreferencesKey(name)] = v.jsonPrimitive.content
                        "stringset" -> prefs[stringSetPreferencesKey(name)] =
                            v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    }
                } catch (_: Exception) {
                    // Skip a single malformed entry rather than aborting restore.
                }
            }
        }
    }

    /**
     * Merge all WAL frames into the main .db file and truncate the log.
     * Best-effort: on failure the backup still proceeds, but the -wal
     * sidecar is then copied alongside so no committed write is lost.
     */
    private fun checkpointWal() {
        try {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(logTag, "WAL checkpoint failed; falling back to copying the -wal sidecar", e)
        }
    }

    /**
     * Copy a non-empty -wal sidecar next to the backed-up .db. The -shm file
     * is deliberately skipped: it's a shared-memory index SQLite rebuilds on
     * open, and a copy taken from a live database is not meaningful.
     */
    private fun copySidecarsIfPresent(dbFile: File, backupFolder: File) {
        val wal = File(dbFile.path + "-wal")
        if (wal.exists() && wal.length() > 0) {
            FileInputStream(wal).use { input ->
                FileOutputStream(File(backupFolder, "$DATABASE_BACKUP_NAME-wal")).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    /** Remove every journal sidecar of [dbFile] (WAL and rollback modes). */
    private fun deleteSidecars(dbFile: File) {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(dbFile.path + suffix)
            if (sidecar.exists() && !sidecar.delete()) {
                Log.w(logTag, "Could not delete ${sidecar.name} before restore")
            }
        }
    }

    private fun restoreSidecarsIfPresent(dbFile: File, backupFolder: File) {
        val walBackup = File(backupFolder, "$DATABASE_BACKUP_NAME-wal")
        if (walBackup.exists()) {
            FileInputStream(walBackup).use { input ->
                FileOutputStream(File(dbFile.path + "-wal")).use { output ->
                    input.copyTo(output)
                }
            }
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

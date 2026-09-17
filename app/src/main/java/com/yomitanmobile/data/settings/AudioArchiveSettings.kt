package com.yomitanmobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yomitanmobile.MainActivity
import com.yomitanmobile.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which folder the pronunciation index was built from.
 *
 * Only the pointer lives here; the index itself is the `audio_files` table.
 * Keeping the two apart is what lets a re-index replace the index in place
 * without the user picking the folder again, and what makes "you have an
 * archive but its files are gone" a state the settings screen can state.
 */
@Singleton
class AudioArchiveSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun folderUri(): String? = read(MainActivity.AUDIO_ARCHIVE_URI)

    /** A short name for the folder, for the settings row. */
    suspend fun folderLabel(): String? = read(MainActivity.AUDIO_ARCHIVE_LABEL)

    suspend fun setFolder(uri: String, label: String) {
        context.dataStore.edit { prefs ->
            prefs[MainActivity.AUDIO_ARCHIVE_URI] = uri
            prefs[MainActivity.AUDIO_ARCHIVE_LABEL] = label
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(MainActivity.AUDIO_ARCHIVE_URI)
            prefs.remove(MainActivity.AUDIO_ARCHIVE_LABEL)
        }
    }

    private suspend fun read(key: androidx.datastore.preferences.core.Preferences.Key<String>) =
        try {
            context.dataStore.data.first()[key]?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
}

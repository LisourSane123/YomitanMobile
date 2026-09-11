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
 * Which frequency list leads, and in what order the rest follow.
 *
 * One stored string, two consumers that used to read it separately: the detail
 * screen, for the order the rank chips appear in, and the rollup that stamps
 * `dictionary_entries.frequency` — the number that ends up on a card, orders
 * search results and decides what the deck generators call too rare.
 */
@Singleton
class FrequencySettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Highest priority first. Empty when the user has never reordered them. */
    suspend fun order(): List<String> = try {
        (context.dataStore.data.first()[MainActivity.FREQUENCY_DISPLAY_ORDER] ?: "")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The list whose rank wins when several rank the same word. Empty means
     * "no preference", and the rollup falls back to the best rank anywhere.
     */
    suspend fun leadingDictionary(): String = order().firstOrNull().orEmpty()

    suspend fun setOrder(order: List<String>) {
        context.dataStore.edit {
            it[MainActivity.FREQUENCY_DISPLAY_ORDER] = order.joinToString(",")
        }
    }
}

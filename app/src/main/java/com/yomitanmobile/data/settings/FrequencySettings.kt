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
     * The leading list among those actually [installed]: the first saved one
     * still installed, else the first installed list. This is the order the
     * frequency screen draws, so the list it shows on top is the list that
     * leads — before the user has ever reordered anything, and after the
     * saved leader has been uninstalled.
     */
    suspend fun leadingDictionary(installed: List<String>): String =
        resolveOrder(order(), installed).firstOrNull().orEmpty()

    /** Saved priority for still-installed lists, then any new ones. */
    fun resolveOrder(saved: List<String>, installed: List<String>): List<String> =
        saved.filter { it in installed } + installed.filter { it !in saved }

    /**
     * True when the leading list is the ONLY source of the stored rank.
     *
     * Off (the default), a word the leading list does not know keeps the best
     * rank another installed list gives it — more words carry a number, at the
     * cost of the number meaning different things on different cards. On, the
     * column holds one list's scale and nothing else, which is what an Anki
     * reorder addon sorts new cards by: two lists interleaved put a word the
     * leading list calls rare ahead of one it calls common.
     */
    suspend fun strictLeading(): Boolean = try {
        context.dataStore.data.first()[MainActivity.FREQUENCY_STRICT_LEADING] ?: false
    } catch (_: Exception) {
        false
    }

    suspend fun setStrictLeading(value: Boolean) {
        context.dataStore.edit { it[MainActivity.FREQUENCY_STRICT_LEADING] = value }
    }

    suspend fun setOrder(order: List<String>) {
        context.dataStore.edit {
            it[MainActivity.FREQUENCY_DISPLAY_ORDER] = order.joinToString(",")
        }
    }
}

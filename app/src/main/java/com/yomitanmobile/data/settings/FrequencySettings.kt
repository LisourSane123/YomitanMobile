package com.yomitanmobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yomitanmobile.MainActivity
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which frequency list leads, and in what order the rest follow — per study
 * language.
 *
 * One stored order per language, two consumers that used to read it
 * separately: the detail screen, for the order the rank chips appear in, and
 * the rollup that stamps `dictionary_entries.frequency` — the number that ends
 * up on a card, orders search results and decides what the deck generators
 * call too rare.
 *
 * Per language because a list counts words of one language. With one shared
 * order, installing an English list either left English cards without a
 * number (JPDB leading: it knows no English word) or took the numbers off
 * every Japanese card (the English list leading, with strict leading on).
 * Japanese keeps the key it always had, so an existing order survives.
 */
@Singleton
class FrequencySettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Highest priority first. Empty when the user has never reordered them. */
    suspend fun order(language: AppLanguage): List<String> = try {
        (context.dataStore.data.first()[orderKey(language)] ?: "")
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
    suspend fun leadingDictionary(language: AppLanguage, installed: List<String>): String =
        resolveOrder(order(language), installed).firstOrNull().orEmpty()

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

    suspend fun setOrder(language: AppLanguage, order: List<String>) {
        context.dataStore.edit {
            it[orderKey(language)] = order.joinToString(",")
        }
    }

    companion object {
        /** Japanese keeps the pre-language key; the others get their own. */
        fun orderKey(language: AppLanguage): Preferences.Key<String> =
            if (language == AppLanguage.JAPANESE) MainActivity.FREQUENCY_DISPLAY_ORDER
            else stringPreferencesKey("frequency_display_order_${language.entryTag}")
    }
}

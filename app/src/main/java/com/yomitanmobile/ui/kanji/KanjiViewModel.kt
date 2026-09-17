package com.yomitanmobile.ui.kanji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One bucket of kanji — a school grade or a JLPT level — and how much of it
 * the user's collection already covers.
 */
data class KanjiBucketState(
    val label: String,
    val grade: Int = 0,
    val jlpt: Int = 0,
    val total: Int = 0,
    val known: Int = 0
) {
    val coverage: Float get() = if (total == 0) 0f else known.toFloat() / total
}

/**
 * The kanji browser.
 *
 * Everything here reads data the app already stored and never used: the kanji
 * banks a KANJIDIC install fills, and the collection scan the duplicate check
 * runs on. The one question it answers that nothing else could — "how much of
 * jōyō grade 3 do I have cards for" — is a join between the two.
 *
 * "Known" is the stored scan's meaning of the word: a card exists carrying
 * this character. It says nothing about recall, and the screen says so.
 */
@HiltViewModel
class KanjiViewModel @Inject constructor(
    private val repository: DictionaryRepository,
    private val ankiCollectionStore: AnkiCollectionStore
) : ViewModel() {

    private val _buckets = MutableStateFlow<List<KanjiBucketState>>(emptyList())
    val buckets: StateFlow<List<KanjiBucketState>> = _buckets.asStateFlow()

    private val _selected = MutableStateFlow<KanjiBucketState?>(null)
    val selected: StateFlow<KanjiBucketState?> = _selected.asStateFlow()

    private val _kanji = MutableStateFlow<List<KanjiEntry>>(emptyList())
    val kanji: StateFlow<List<KanjiEntry>> = _kanji.asStateFlow()

    private val _knownKanji = MutableStateFlow<Set<String>>(emptySet())
    val knownKanji: StateFlow<Set<String>> = _knownKanji.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** No kanji dictionary installed — the screen offers KANJIDIC instead. */
    private val _empty = MutableStateFlow(false)
    val empty: StateFlow<Boolean> = _empty.asStateFlow()

    /**
     * True when the kanji rows carry no grade or JLPT at all.
     *
     * That is what a KANJIDIC imported before the parser read those fields
     * looks like, and the difference matters: "no kanji dictionary" and
     * "a kanji dictionary that predates this screen" need different advice.
     */
    private val _needsReimport = MutableStateFlow(false)
    val needsReimport: StateFlow<Boolean> = _needsReimport.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val known = runCatching { ankiCollectionStore.knownKanji() }.getOrDefault(emptySet())
            _knownKanji.value = known

            val grades = repository.kanjiCountsByGrade()
            val jlpt = repository.kanjiCountsByJlpt()
            val all = repository.listKanji()
            _empty.value = all.isEmpty()
            _needsReimport.value = all.isNotEmpty() && grades.isEmpty() && jlpt.isEmpty()

            val knownPerBucket = { rows: List<KanjiEntry> -> rows.count { it.kanji in known } }
            val buckets = ArrayList<KanjiBucketState>()
            buckets += KanjiBucketState(
                label = ALL,
                total = all.size,
                known = knownPerBucket(all)
            )
            for (row in jlpt) {
                val members = all.filter { it.jlpt == row.bucket }
                buckets += KanjiBucketState(
                    label = "N${row.bucket}",
                    jlpt = row.bucket,
                    total = members.size,
                    known = knownPerBucket(members)
                )
            }
            for (row in grades) {
                val members = all.filter { it.grade == row.bucket }
                buckets += KanjiBucketState(
                    label = gradeLabel(row.bucket),
                    grade = row.bucket,
                    total = members.size,
                    known = knownPerBucket(members)
                )
            }
            _buckets.value = buckets
            select(_selected.value?.let { current -> buckets.firstOrNull { it.label == current.label } }
                ?: buckets.firstOrNull())
            _loading.value = false
        }
    }

    fun select(bucket: KanjiBucketState?) {
        _selected.value = bucket
        viewModelScope.launch {
            _kanji.value = when {
                bucket == null -> emptyList()
                else -> repository.listKanji(grade = bucket.grade, jlpt = bucket.jlpt)
            }
        }
    }

    companion object {
        const val ALL = "∑"

        /**
         * KANJIDIC grades 1-6 are the school years, 8 is the rest of jōyō and
         * 9/10 are the name kanji — worth naming, because "grade 8" means
         * nothing to a learner and "jōyō, secondary school" does.
         */
        fun gradeLabel(grade: Int): String = when (grade) {
            in 1..6 -> "常用 $grade"
            8 -> "常用 +"
            9, 10 -> "人名"
            else -> "$grade"
        }
    }
}

/** The one-character screen: its row, the words it is written in, its state. */
@HiltViewModel
class KanjiDetailViewModel @Inject constructor(
    private val repository: DictionaryRepository,
    private val ankiCollectionStore: AnkiCollectionStore,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val character: String = savedStateHandle.get<String>("kanji").orEmpty()

    private val _entry = MutableStateFlow<KanjiEntry?>(null)
    val entry: StateFlow<KanjiEntry?> = _entry.asStateFlow()

    private val _words = MutableStateFlow<List<WordEntry>>(emptyList())
    val words: StateFlow<List<WordEntry>> = _words.asStateFlow()

    private val _known = MutableStateFlow(false)
    val known: StateFlow<Boolean> = _known.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val kanji: String get() = character

    init {
        viewModelScope.launch {
            _entry.value = repository.getKanji(character)
            // Words first, collection second: the list is what the screen is
            // for, and the scan can be slow or absent.
            _words.value = repository.wordsContainingKanji(character)
            _loading.value = false
            _known.value = runCatching {
                character in ankiCollectionStore.knownKanji()
            }.getOrDefault(false)
        }
    }
}

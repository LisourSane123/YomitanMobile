package com.yomitanmobile.ui.kanji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.anki.KanjiStudyExport
import com.yomitanmobile.data.anki.KanjiTally
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    val known: Int = 0,
    /** Of [known], how many are carried by a card the user actually knows. */
    val mature: Int = 0
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
    private val ankiCollectionStore: AnkiCollectionStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _buckets = MutableStateFlow<List<KanjiBucketState>>(emptyList())
    val buckets: StateFlow<List<KanjiBucketState>> = _buckets.asStateFlow()

    private val _selected = MutableStateFlow<KanjiBucketState?>(null)
    val selected: StateFlow<KanjiBucketState?> = _selected.asStateFlow()

    private val _kanji = MutableStateFlow<List<KanjiEntry>>(emptyList())
    val kanji: StateFlow<List<KanjiEntry>> = _kanji.asStateFlow()

    private val _knownKanji = MutableStateFlow<Set<String>>(emptySet())
    val knownKanji: StateFlow<Set<String>> = _knownKanji.asStateFlow()

    /**
     * Kanji carried by a MATURE card. A subset of [knownKanji] — the screen
     * shows both, because "I made a card yesterday" and "I know this" are
     * different claims and only the second is worth a coverage bar.
     */
    private val _matureKanji = MutableStateFlow<Set<String>>(emptySet())
    val matureKanji: StateFlow<Set<String>> = _matureKanji.asStateFlow()

    /**
     * Every kanji in the collection with how many of the user's words carry it,
     * commonest first — the same list twice, once for all cards and once for
     * mature ones only, because both are a pass over words already in memory
     * and the screen switches between them with a toggle.
     *
     * This half of the screen needs no kanji dictionary at all: it is the scan
     * cut into characters, nothing else.
     */
    private val _tally = MutableStateFlow(KanjiTally.KanjiTallyResult.EMPTY)
    val tally: StateFlow<KanjiTally.KanjiTallyResult> = _tally.asStateFlow()

    private val _matureTally = MutableStateFlow(KanjiTally.KanjiTallyResult.EMPTY)
    val matureTally: StateFlow<KanjiTally.KanjiTallyResult> = _matureTally.asStateFlow()

    /**
     * Whether the counts, and the export made from them, are restricted to
     * words on mature cards. Lives here rather than in the composable because
     * the export reads it too, and the two must not be able to disagree.
     */
    private val _matureOnly = MutableStateFlow(false)
    val matureOnly: StateFlow<Boolean> = _matureOnly.asStateFlow()

    /** KANJIDIC's newspaper rank per character; empty until a kanji bank is installed. */
    private var mediaRank: Map<String, Int> = emptyMap()

    /** What an export would contain right now — see [KanjiStudyExport]. */
    private val _exportPlan = MutableStateFlow(EMPTY_PLAN)
    val exportPlan: StateFlow<KanjiStudyExport.Plan> = _exportPlan.asStateFlow()

    /** Set when a file is ready to hand to another app; the screen clears it. */
    private val _shareFile = MutableStateFlow<Uri?>(null)
    val shareFile: StateFlow<Uri?> = _shareFile.asStateFlow()

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
            // One pass each, and the sets fall out of the counts — the tally
            // IS "which kanji do I have", with the numbers left in.
            val counted = runCatching { ankiCollectionStore.kanjiTally() }
                .getOrDefault(KanjiTally.KanjiTallyResult.EMPTY)
            val countedMature = runCatching { ankiCollectionStore.kanjiTally(matureOnly = true) }
                .getOrDefault(KanjiTally.KanjiTallyResult.EMPTY)
            _tally.value = counted
            _matureTally.value = countedMature
            val known = counted.counts.mapTo(HashSet()) { it.kanji }
            val mature = countedMature.counts.mapTo(HashSet()) { it.kanji }
            _knownKanji.value = known
            _matureKanji.value = mature

            val grades = repository.kanjiCountsByGrade()
            val jlpt = repository.kanjiCountsByJlpt()
            val all = repository.listKanji()
            mediaRank = all.filter { it.frequency > 0 }.associate { it.kanji to it.frequency }
            recomputeExport()
            _empty.value = all.isEmpty()
            _needsReimport.value = all.isNotEmpty() && grades.isEmpty() && jlpt.isEmpty()

            val buckets = ArrayList<KanjiBucketState>()
            fun bucketOf(label: String, members: List<KanjiEntry>, grade: Int = 0, jlpt: Int = 0) =
                KanjiBucketState(
                    label = label,
                    grade = grade,
                    jlpt = jlpt,
                    total = members.size,
                    known = members.count { it.kanji in known },
                    mature = members.count { it.kanji in mature }
                )
            buckets += bucketOf(ALL, all)
            for (row in jlpt) {
                buckets += bucketOf("N${row.bucket}", all.filter { it.jlpt == row.bucket }, jlpt = row.bucket)
            }
            for (row in grades) {
                buckets += bucketOf(
                    gradeLabel(row.bucket),
                    all.filter { it.grade == row.bucket },
                    grade = row.bucket
                )
            }
            _buckets.value = buckets
            select(_selected.value?.let { current -> buckets.firstOrNull { it.label == current.label } }
                ?: buckets.firstOrNull())
            _loading.value = false
        }
    }

    fun setMatureOnly(value: Boolean) {
        _matureOnly.value = value
        recomputeExport()
    }

    private fun recomputeExport() {
        val counts = (if (_matureOnly.value) _matureTally.value else _tally.value).counts
        _exportPlan.value = KanjiStudyExport.plan(counts, mediaRank = { mediaRank[it] ?: 0 })
    }

    /**
     * Writes the list to a document the user picked. Kanji Study's own
     * "import from file" reads exactly this.
     */
    fun exportToFile(target: Uri) {
        val text = KanjiStudyExport.text(_exportPlan.value)
        if (text.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(text.toByteArray())
                    }
                }.onFailure { Log.w(TAG, "Writing the kanji list failed", it) }
            }
        }
    }

    /**
     * The same text as a file in the cache, for handing to another app —
     * Kanji Study imports directly from a share since its 6.0.0.
     */
    fun prepareShare() {
        val text = KanjiStudyExport.text(_exportPlan.value)
        if (text.isEmpty()) return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, KanjiStudyExport.FILE_NAME)
                    file.writeText(text)
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                }.onFailure { Log.w(TAG, "Preparing the kanji list for sharing failed", it) }
                    .getOrNull()
            }
            _shareFile.value = uri
        }
    }

    fun shareHandled() {
        _shareFile.value = null
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
        private const val TAG = "KanjiViewModel"
        private val EMPTY_PLAN = KanjiStudyExport.plan(emptyList())

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

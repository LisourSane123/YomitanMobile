package com.yomitanmobile.util

/**
 * Reduces an inflected English word to the base forms a dictionary lists.
 *
 * The English counterpart of [JapaneseDeconjugator], and deliberately the
 * same shape: rule-based, no external library, returning CANDIDATES rather
 * than one answer. English inflection is ambiguous without a lexicon —
 * "saw" is both a noun and the past of "see", "leaves" is both — so the
 * search runs every candidate and lets the dictionary decide which ones
 * exist. A wrong candidate simply matches nothing.
 *
 * Rules cover what regular English inflection actually does:
 *   • plural / 3rd person: -s, -es, -ies → -y
 *   • past / participle: -ed, -ied → -y, plus consonant de-doubling
 *   • progressive: -ing, with a restored -e (making → make) and de-doubling
 *   • comparative / superlative: -er, -est
 *
 * Irregular forms come from [IRREGULAR], a closed table, and go FIRST:
 * nothing about "children" says "child", and kty-en-pl — the English-Polish
 * dictionary — has no headword for 70 of the forms it lists (children, men,
 * women, feet, teeth, mice, knives, crises, criteria…), so typing one found
 * nothing. Where the dictionary does list a form (went), the base (go) now
 * comes up beside it. A table rather than more suffix rules on purpose: rules
 * for -ves, -ices, -a or -i offer REAL words as wrong answers — believes →
 * belief, saves → safe, india → indium, suffices → suffix — and a candidate
 * that exists is one the dictionary cannot reject.
 */
object EnglishLemmatizer {

    private const val VOWELS = "aeiou"

    /** Longest first, so the most specific suffix rule gets its turn. */
    private val SUFFIXES = listOf("iest", "ies", "ied", "est", "ing", "ed", "er", "es", "s")

    /**
     * Base-form candidates for [word], most likely first, never including
     * the input itself (the caller always searches that separately).
     *
     * Capped like the deconjugator's candidate list: each candidate costs a
     * parallel query, and beyond a handful they are noise.
     */
    fun analyze(word: String, limit: Int = 8): List<String> {
        val normalized = word.trim().lowercase()
        if (normalized.length < 3) return emptyList()
        if (!normalized.all { it.isLetter() || it == '-' || it == '\'' }) return emptyList()

        val candidates = LinkedHashSet<String>()
        IRREGULAR[normalized]?.let { candidates.addAll(it) }

        for (suffix in SUFFIXES) {
            if (!normalized.endsWith(suffix)) continue
            val stem = normalized.dropLast(suffix.length)
            if (stem.length < 2) continue

            when (suffix) {
                // studies → study, tried → try
                "ies", "ied", "iest" -> candidates.add(stem + "y")
                else -> {
                    candidates.add(stem)
                    // making → make, larger → large: the silent -e that the
                    // suffix displaced.
                    if (suffix == "ing" || suffix == "ed" || suffix == "er" || suffix == "est") {
                        candidates.add(stem + "e")
                    }
                    // running → run, bigger → big.
                    undoubleFinalConsonant(stem)?.let { candidates.add(it) }
                }
            }
        }

        // boxes → box, watches → watch. Handled after the generic -es rule
        // above so "boxe" is offered first only where it is a real stem.
        if (normalized.endsWith("es") && normalized.length > 3) {
            candidates.add(normalized.dropLast(2))
        }

        candidates.remove(normalized)
        return candidates.take(limit)
    }

    /**
     * Irregular form → its base forms, parsed from [IRREGULAR_TABLE]. Every
     * base was checked to be a headword of kty-en-pl, so none is a typo.
     */
    private val IRREGULAR: Map<String, List<String>> by lazy { parseIrregular(IRREGULAR_TABLE) }

    /**
     * `form base[,base]` for nouns and comparatives; `base past participle`
     * (commas for alternatives) for verbs, under the `# verbs` header.
     */
    internal fun parseIrregular(table: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        fun add(form: String, base: String) {
            if (form == base) return
            val list = out.getOrPut(form) { ArrayList(2) }
            if (base !in list) list += base
        }
        var verbs = false
        for (raw in table.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) { verbs = "verbs" in line; continue }
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (verbs) {
                val base = parts.first()
                parts.drop(1).flatMap { it.split(',') }.forEach { add(it, base) }
            } else if (parts.size >= 2) {
                parts[1].split(',').forEach { add(parts[0], it) }
            }
        }
        return out
    }

    private val IRREGULAR_TABLE = """
        # noun plurals
        men man
        women woman
        children child
        feet foot
        teeth tooth
        geese goose
        mice mouse
        lice louse
        oxen ox
        people person
        dice die
        pence penny
        brethren brother
        # classical and -ves plurals
        wives wife
        knives knife
        lives life
        wolves wolf
        halves half
        leaves leaf
        shelves shelf
        thieves thief
        loaves loaf
        calves calf
        elves elf
        selves self
        scarves scarf
        hooves hoof
        sheaves sheaf
        dwarves dwarf
        indices index
        matrices matrix
        vertices vertex
        appendices appendix
        vortices vortex
        apices apex
        codices codex
        crises crisis
        theses thesis
        hypotheses hypothesis
        analyses analysis
        diagnoses diagnosis
        parentheses parenthesis
        syntheses synthesis
        emphases emphasis
        oases oasis
        axes axis,axe
        bases basis,base
        neuroses neurosis
        prognoses prognosis
        synopses synopsis
        ellipses ellipsis
        metamorphoses metamorphosis
        criteria criterion
        phenomena phenomenon
        data datum
        media medium
        bacteria bacterium
        curricula curriculum
        memoranda memorandum
        strata stratum
        millennia millennium
        spectra spectrum
        automata automaton
        errata erratum
        addenda addendum
        cacti cactus
        fungi fungus
        nuclei nucleus
        stimuli stimulus
        alumni alumnus
        radii radius
        syllabi syllabus
        foci focus
        loci locus
        termini terminus
        larvae larva
        algae alga
        vertebrae vertebra
        antennae antenna
        formulae formula
        nebulae nebula
        alumnae alumna
        # comparatives / superlatives
        better good,well
        best good,well
        worse bad,badly
        worst bad,badly
        more much,many
        most much,many
        less little
        least little
        further far
        furthest far
        farther far
        farthest far
        elder old
        eldest old
        # verbs: base past participle
        arise arose arisen
        awake awoke awoken
        be was,were been
        bear bore borne,born
        beat beat beaten
        become became become
        begin began begun
        bend bent bent
        bet bet bet
        bind bound bound
        bite bit bitten
        bleed bled bled
        blow blew blown
        break broke broken
        breed bred bred
        bring brought brought
        build built built
        burn burnt burnt
        burst burst burst
        buy bought bought
        cast cast cast
        catch caught caught
        choose chose chosen
        cling clung clung
        come came come
        cost cost cost
        creep crept crept
        cut cut cut
        deal dealt dealt
        dig dug dug
        do did done
        draw drew drawn
        dream dreamt dreamt
        drink drank drunk
        drive drove driven
        eat ate eaten
        fall fell fallen
        feed fed fed
        feel felt felt
        fight fought fought
        find found found
        flee fled fled
        fling flung flung
        fly flew flown
        forbid forbade forbidden
        forget forgot forgotten
        forgive forgave forgiven
        freeze froze frozen
        get got got,gotten
        give gave given
        go went gone
        grind ground ground
        grow grew grown
        hang hung hung
        have had had
        hear heard heard
        hide hid hidden
        hit hit hit
        hold held held
        hurt hurt hurt
        keep kept kept
        kneel knelt knelt
        know knew known
        lay laid laid
        lead led led
        lean leant leant
        leap leapt leapt
        learn learnt learnt
        leave left left
        lend lent lent
        let let let
        lie lay lain
        light lit lit
        lose lost lost
        make made made
        mean meant meant
        meet met met
        mistake mistook mistaken
        pay paid paid
        prove proved proven
        put put put
        quit quit quit
        read read read
        ride rode ridden
        ring rang rung
        rise rose risen
        run ran run
        say said said
        see saw seen
        seek sought sought
        sell sold sold
        send sent sent
        set set set
        sew sewed sewn
        shake shook shaken
        shed shed shed
        shine shone shone
        shoot shot shot
        show showed shown
        shrink shrank shrunk
        shut shut shut
        sing sang sung
        sink sank sunk
        sit sat sat
        slay slew slain
        sleep slept slept
        slide slid slid
        sling slung slung
        slit slit slit
        smell smelt smelt
        sow sowed sown
        speak spoke spoken
        speed sped sped
        spell spelt spelt
        spend spent spent
        spill spilt spilt
        spin spun spun
        spit spat spat
        split split split
        spoil spoilt spoilt
        spread spread spread
        spring sprang sprung
        stand stood stood
        steal stole stolen
        stick stuck stuck
        sting stung stung
        stink stank stunk
        stride strode stridden
        strike struck struck,stricken
        string strung strung
        strive strove striven
        swear swore sworn
        sweep swept swept
        swell swelled swollen
        swim swam swum
        swing swung swung
        take took taken
        teach taught taught
        tear tore torn
        tell told told
        think thought thought
        throw threw thrown
        thrust thrust thrust
        tread trod trodden
        understand understood understood
        undertake undertook undertaken
        wake woke woken
        wear wore worn
        weave wove woven
        weep wept wept
        win won won
        wind wound wound
        withdraw withdrew withdrawn
        wring wrung wrung
        write wrote written
    """.trimIndent()

    /**
     * "running" → "run" once the -ing is gone: a stem ending in a doubled
     * consonant after a single vowel.
     *
     * This cannot be decided correctly on its own — "falling" has exactly
     * the same shape, and there "fall" is the answer. Both are emitted, with
     * the plain stem first (see [analyze]); the dictionary is what settles
     * it, and the wrong one matches nothing. The preceding-vowel check still
     * earns its keep by rejecting the many stems where the doubling isn't
     * inflectional at all ("miss", "add", "off").
     */
    private fun undoubleFinalConsonant(stem: String): String? {
        if (stem.length < 3) return null
        val last = stem[stem.lastIndex]
        val secondLast = stem[stem.lastIndex - 1]
        if (last != secondLast) return null
        if (last in VOWELS) return null
        val beforePair = stem[stem.lastIndex - 2]
        if (beforePair !in VOWELS) return null
        return stem.dropLast(1)
    }
}

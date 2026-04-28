package com.yomitanmobile.util

import com.yomitanmobile.domain.model.WordEntry

/**
 * Classifies mined words into the most important learning categories.
 */
object WordCategoryClassifier {

    const val CATEGORY_FOOD = "FOOD"
    const val CATEGORY_TRAVEL = "TRAVEL"
    const val CATEGORY_ECONOMY = "ECONOMY"
    const val CATEGORY_WORK = "WORK"
    const val CATEGORY_EDUCATION = "EDUCATION"
    const val CATEGORY_HEALTH = "HEALTH"
    const val CATEGORY_TECHNOLOGY = "TECHNOLOGY"
    const val CATEGORY_SHOPPING = "SHOPPING"
    const val CATEGORY_HOME_DAILY = "HOME_DAILY"
    const val CATEGORY_CULTURE = "CULTURE"
    const val CATEGORY_RELATIONSHIPS = "RELATIONSHIPS"
    const val CATEGORY_OTHER = "OTHER"

    private data class CategoryRule(
        val code: String,
        val labelPl: String,
        val labelEn: String,
        val keywords: Set<String>
    )

    private val rules = listOf(
        CategoryRule(
            CATEGORY_FOOD,
            "Jedzenie",
            "Food",
            setOf(
                "food", "eat", "meal", "drink", "restaurant", "kitchen", "cooking", "recipe",
                "rice", "fish", "vegetable", "fruit", "meat", "coffee", "tea", "breakfast", "lunch", "dinner",
                "jedzenie", "posilek", "napoj", "gotowanie",
                "食", "飲", "料理", "ご飯", "弁当", "レストラン"
            )
        ),
        CategoryRule(
            CATEGORY_TRAVEL,
            "Podroze",
            "Travel",
            setOf(
                "travel", "trip", "journey", "airport", "station", "train", "bus", "ticket", "hotel", "map", "passport",
                "podroz", "lotnisko", "pociag", "autobus", "bilet", "hotel",
                "旅行", "空港", "駅", "電車", "切符", "ホテル"
            )
        ),
        CategoryRule(
            CATEGORY_ECONOMY,
            "Ekonomia",
            "Economy",
            setOf(
                "economy", "economic", "finance", "financial", "bank", "inflation", "market", "investment", "stock", "tax",
                "budget", "loan", "interest rate", "currency",
                "ekonomia", "finanse", "bank", "inwestycja", "podatek", "rynek",
                "経済", "金融", "銀行", "株", "投資", "税"
            )
        ),
        CategoryRule(
            CATEGORY_WORK,
            "Praca",
            "Work",
            setOf(
                "work", "job", "office", "company", "business", "meeting", "manager", "employee", "career", "salary",
                "deadline", "project", "client",
                "praca", "firma", "biuro", "spotkanie", "projekt", "pensja",
                "仕事", "会社", "会議", "上司", "社員", "給料"
            )
        ),
        CategoryRule(
            CATEGORY_EDUCATION,
            "Edukacja",
            "Education",
            setOf(
                "school", "study", "learn", "education", "student", "teacher", "class", "lesson", "exam", "university",
                "homework", "dictionary", "grammar",
                "edukacja", "nauka", "uczen", "nauczyciel", "lekcja", "egzamin",
                "勉強", "学校", "授業", "試験", "先生", "学生"
            )
        ),
        CategoryRule(
            CATEGORY_HEALTH,
            "Zdrowie",
            "Health",
            setOf(
                "health", "medical", "doctor", "hospital", "medicine", "symptom", "disease", "pain", "therapy", "treatment",
                "exercise", "sleep", "diet",
                "zdrowie", "lekarz", "szpital", "lek", "choroba", "objaw",
                "健康", "病院", "医者", "薬", "病気", "症状"
            )
        ),
        CategoryRule(
            CATEGORY_TECHNOLOGY,
            "Technologia",
            "Technology",
            setOf(
                "technology", "computer", "software", "hardware", "app", "application", "internet", "network", "device", "data",
                "security", "ai", "machine learning", "database", "code", "programming",
                "technologia", "komputer", "aplikacja", "internet", "siec", "dane",
                "技術", "コンピュータ", "ソフト", "アプリ", "ネット", "データ"
            )
        ),
        CategoryRule(
            CATEGORY_SHOPPING,
            "Zakupy",
            "Shopping",
            setOf(
                "shop", "shopping", "buy", "sell", "price", "cheap", "expensive", "store", "marketplace", "payment",
                "discount", "receipt", "customer",
                "zakupy", "kupic", "sprzedac", "cena", "rabat", "klient",
                "買", "売", "値段", "店", "支払い", "割引"
            )
        ),
        CategoryRule(
            CATEGORY_HOME_DAILY,
            "Dom i codziennosc",
            "Home & daily life",
            setOf(
                "home", "house", "family", "daily", "routine", "clean", "laundry", "cook", "room", "kitchen", "bathroom",
                "morning", "evening", "weekend",
                "dom", "rodzina", "codzienny", "sprzatanie", "pokoj", "kuchnia",
                "家", "家庭", "日常", "部屋", "朝", "夜"
            )
        ),
        CategoryRule(
            CATEGORY_CULTURE,
            "Kultura i rozrywka",
            "Culture & entertainment",
            setOf(
                "culture", "music", "movie", "film", "book", "anime", "manga", "game", "art", "festival", "concert",
                "museum", "theater", "hobby",
                "kultura", "muzyka", "film", "ksiazka", "anime", "manga", "gra",
                "文化", "音楽", "映画", "本", "祭", "趣味"
            )
        ),
        CategoryRule(
            CATEGORY_RELATIONSHIPS,
            "Relacje",
            "Relationships",
            setOf(
                "relationship", "friend", "family", "partner", "marriage", "love", "emotion", "feeling", "communication", "talk",
                "conflict", "support", "trust",
                "relacja", "przyjaciel", "partner", "milosc", "emocja", "rozmowa",
                "関係", "友達", "家族", "恋愛", "感情", "会話"
            )
        )
    )

    private fun isAsciiKeyword(keyword: String): Boolean {
        return keyword.all {
            it.code <= 0x7F && (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '+')
        }
    }

    private fun matchesKeyword(haystack: String, keyword: String): Boolean {
        val normalized = keyword.lowercase()
        if (normalized.isBlank()) return false

        return if (isAsciiKeyword(normalized)) {
            val pattern = Regex("\\b${Regex.escape(normalized)}\\b")
            pattern.containsMatchIn(haystack)
        } else {
            haystack.contains(normalized)
        }
    }

    fun classify(entry: WordEntry): String {
        val haystack = buildString {
            append(entry.expression)
            append(' ')
            append(entry.reading)
            append(' ')
            append(entry.partsOfSpeech)
            append(' ')
            append(entry.definitionText())
            append(' ')
            append(entry.exampleSentence)
            append(' ')
            append(entry.exampleSentenceTranslation)
        }.lowercase()

        for (rule in rules) {
            if (rule.keywords.any { keyword -> matchesKeyword(haystack, keyword) }) {
                return rule.code
            }
        }

        return CATEGORY_OTHER
    }

    fun displayName(categoryCode: String, isEnglish: Boolean = false): String {
        val match = rules.firstOrNull { it.code == categoryCode }
        if (match == null) return if (isEnglish) "Other" else "Inne"
        return if (isEnglish) match.labelEn else match.labelPl
    }

    fun mostImportantCategories(isEnglish: Boolean = false): List<Pair<String, String>> {
        val primary = rules.map { rule ->
            rule.code to if (isEnglish) rule.labelEn else rule.labelPl
        }
        return primary + (CATEGORY_OTHER to if (isEnglish) "Other" else "Inne")
    }
}

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
    const val CATEGORY_ANIMALS = "ANIMALS"
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
                "food", "eat", "meal", "drink", "restaurant", "kitchen", "cooking", "recipe", "cuisine", "taste", "flavor",
                "rice", "fish", "vegetable", "fruit", "meat", "coffee", "tea", "breakfast", "lunch", "dinner", "snack", "dessert",
                "bread", "pasta", "soup", "salad", "sauce", "salt", "sugar", "spice", "oil", "butter", "cheese", "yogurt", "milk", "egg",
                "chicken", "beef", "pork", "lamb", "turkey", "duck", "shrimp", "crab", "lobster", "scallop", "mussel", "clam", "oyster",
                "tofu", "noodle", "rice noodle", "pizza", "sushi", "tempura", "curry", "ramen", "udon", "soba", "gyoza", "wontons",
                "beer", "wine", "sake", "juice", "smoothie", "milkshake", "soda", "pop", "soy sauce", "miso", "vinegar", "sesame",
                "appetizer", "entrée", "side dish", "course", "portion", "serving", "portion", "tablespoon", "teaspoon", "cup",
                "pan", "pot", "wok", "skillet", "oven", "stove", "microwave", "grill", "blender", "knife", "fork", "spoon", "chopstick",
                "fry", "boil", "grill", "bake", "roast", "steam", "broil", "sauté", "simmer", "stew", "braise", "poach", "chop", "slice", "dice",
                "tender", "juicy", "crispy", "creamy", "spicy", "sweet", "salty", "bitter", "sour", "umami", "bland", "delicious", "appetizing",
                "organic", "fresh", "frozen", "canned", "processed", "natural", "gluten-free", "dairy-free", "vegan", "vegetarian", "allergen",
                "calories", "protein", "fat", "carbohydrate", "vitamin", "mineral", "nutrient", "diet", "nutrition", "nutritious", "fatty",
                // fruits
                "apple", "banana", "orange", "lemon", "lime", "grape", "grapes", "strawberry", "blueberry", "raspberry", "blackberry",
                "mango", "papaya", "pineapple", "pear", "peach", "nectarine", "plum", "cherry", "apricot", "kiwi", "coconut",
                "avocado", "melon", "watermelon", "canteloupe", "fig", "date", "pomegranate", "grapefruit", "tangerine", "clementine",
                // vegetables
                "potato", "potatoes", "tomato", "tomatoes", "carrot", "carrots", "onion", "onions", "garlic", "ginger", "leek",
                "scallion", "spring onion", "celery", "cucumber", "lettuce", "spinach", "kale", "cabbage", "broccoli", "cauliflower",
                "eggplant", "aubergine", "zucchini", "courgette", "pumpkin", "squash", "butternut", "bell pepper", "capsicum", "chili",
                "radish", "beet", "beetroot", "yam", "sweet potato", "okra", "asparagus", "pea", "peas", "corn", "maize",
                // spices & herbs
                "cinnamon", "nutmeg", "clove", "cloves", "cardamom", "cumin", "coriander", "turmeric", "turmeric root", "paprika",
                "chili powder", "black pepper", "pepper", "white pepper", "mustard", "mustard seed", "fennel", "anise", "star anise",
                "saffron", "vanilla", "bay leaf", "oregano", "thyme", "rosemary", "basil", "parsley", "cilantro", "coriander leaf",
                "dill", "mint", "sage", "tarragon", "lemongrass", "sesame seed", "sesame", "wasabi", "horseradish", "soy", "miso",
                "jedzenie", "posilek", "napoj", "gotowanie", "receptura", "smak", "kuchnia", "chleb", "maka", "mleko", "ser", "mlode",
                "sery", "maso", "ryba", "warzyw", "owoc", "zupa", "sałat", "deser", "ciastko", "piekarnia", "restauracja", "biegus",
                "pielęgnacja", "gotowanie", "rozpuszcze", "obiad", "śniadanie", "kolacja", "płatki", "herbata", "kawe", "woda",
                "食", "飲", "料理", "ご飯", "弁当", "レストラン", "味", "塩", "砂糖", "油", "バター", "チーズ", "卵", "鶏",
                "牛", "豚", "羊", "新鮮", "冷凍", "缶詰", "塩辛", "甘", "辛", "酸っぱい", "風味", "美味", "不健康", "栄養",
                "おいしい", "まずい", "料理", "調理", "焼く", "煮込む", "蒸す", "炒める", "揚げる", "煮る", "グリル", "おかず"
            )
        ),
        CategoryRule(
            CATEGORY_ANIMALS,
            "Zwierzęta",
            "Animals",
            setOf(
                "animal", "animals", "pet", "pets", "livestock", "wildlife", "wild animal", "domestic animal",
                "mammal", "mammals", "bird", "birds", "reptile", "reptiles", "amphibian", "amphibians", "insect", "insects",
                "arachnid", "arachnids", "fish", "fishes", "crustacean", "crustaceans", "mollusk", "mollusks", "vertebrate", "vertebrates",
                // common farm & domestic
                "cow", "cows", "cattle", "bull", "bullock", "ox", "calf", "calves", "horse", "horses", "mare", "stallion",
                "pig", "pigs", "piglet", "sheep", "lamb", "goat", "goats", "chicken", "hen", "rooster", "duck", "goose", "turkey", "rabbit",
                "rabbits", "hare", "llama", "alpaca", "donkey", "mule", "camel", "llama", "alpaca", "yak",
                // common pets
                "dog", "dogs", "puppy", "puppies", "canine", "cat", "cats", "kitten", "kittens", "feline", "goldfish", "guinea pig",
                "hamster", "hamsters", "gerbil", "gerbils", "parrot", "parrots", "budgie", "budgerigar", "cockatiel", "canary", "ferret",
                "rabbit", "bunny", "bunnies", "mouse", "mice", "rat", "rats", "squirrel", "squirrels", "hedgehog", "hedgehogs",
                // wildlife
                "lion", "tiger", "leopard", "cheetah", "panther", "jaguar", "bear", "elephant", "giraffe", "zebra", "monkey", "ape", "gorilla", "chimpanzee",
                "orangutan", "baboon", "macaque", "wolf", "fox", "coyote", "jackal", "deer", "moose", "elk", "antelope", "bison", "buffalo",
                "rhinoceros", "hippopotamus", "badger", "raccoon", "otter", "skunk", "beaver", "lynx", "bobcat", "wild boar", "boar",
                // marine
                "shark", "whale", "dolphin", "porpoise", "seal", "sea lion", "walrus", "octopus", "squid", "crab", "lobster", "seal",
                // birds
                "eagle", "hawk", "falcon", "owl", "sparrow", "pigeon", "dove", "seagull", "swan", "flamingo", "crow", "raven", "magpie",
                "penguin", "penguins", "parakeet", "woodpecker", "hummingbird", "stork", "heron", "crane",
                // reptiles and amphibians
                "snake", "snakes", "lizard", "lizards", "turtle", "turtles", "tortoise", "tortoises", "crocodile", "alligator",
                "gecko", "iguana", "chameleon", "frog", "toad", "salamander", "newt",
                // insects & small creatures
                "bee", "bees", "butterfly", "butterflies", "moth", "moths", "ant", "ants", "spider", "spiders", "bug", "bugs", "insect", "cricket", "grasshopper", "worm",
                "mosquito", "fly", "flies", "beetle", "ladybug", "dragonfly", "caterpillar", "snail", "slug",
                // Polish
                "zwierzeta", "zwierze", "zwierzę", "zwierzęta", "krowa", "koń", "świnia", "owca", "koza", "kurczak", "pies", "kot",
                "ptak", "ryba", "gad", "płaz", "ssak", "owad", "pająk", "gady", "płazy", "owady",
                // Japanese
                "動物", "どうぶつ", "犬", "猫", "牛", "馬", "豚", "羊", "鶏", "魚", "鳥", "虫", "哺乳類", "爬虫類", "両生類", "昆虫", "爬虫類", "鳥類"
            )
        ),
        CategoryRule(
            CATEGORY_TRAVEL,
            "Podroze",
            "Travel",
            setOf(
                "travel", "trip", "journey", "adventure", "expedition", "vacation", "holiday", "getaway", "tour", "excursion",
                "airport", "station", "terminal", "gate", "platform", "schedule", "timetable", "departure", "arrival", "connection",
                "train", "bus", "subway", "metro", "tram", "taxi", "car", "plane", "airplane", "aircraft", "helicopter", "boat", "ship",
                "bike", "bicycle", "motorcycle", "scooter", "truck", "van", "rv", "caravan", "ferry", "cruise", "yacht", "liner",
                "ticket", "boarding pass", "passport", "visa", "traveler's check", "currency", "exchange", "customs", "border",
                "hotel", "motel", "hostel", "lodge", "inn", "bed and breakfast", "resort", "villa", "cottage", "cabin", "campground",
                "check-in", "check-out", "reservation", "booking", "accommodation", "lodging", "amenities", "facilities", "suite", "room",
                "luggage", "baggage", "suitcase", "backpack", "rucksack", "travel bag", "duffel", "trunk", "pack", "carry-on",
                "destination", "route", "itinerary", "path", "course", "direction", "map", "compass", "gps", "navigation", "landmark",
                "tourist", "traveler", "tourist attraction", "landmark", "monument", "temple", "shrine", "museum", "gallery", "theater",
                "beach", "mountain", "valley", "forest", "lake", "river", "ocean", "coast", "island", "city", "countryside", "village",
                "tour guide", "tour operator", "travel agency", "concierge", "bellhop", "porter", "receptionist", "staff",
                "sightseeing", "excursion", "adventure", "activity", "experience", "tour", "expedition", "trek", "safari", "cruise",
                "fare", "price", "cost", "charge", "fee", "rate", "discount", "upgrade", "refund", "cancellation", "insurance",
                "podroz", "lotnisko", "pociag", "autobus", "bilet", "hotel", "samolot", "pasazer", "trasa", "kierowca", "mapa",
                "rezerwacja", "bagaz", "wiza", "paszport", "turustyka", "przewodnik", "atrakcja", "wycieczka", "droga", "noc",
                "旅行", "空港", "駅", "電車", "切符", "ホテル", "飛行機", "乗客", "目的地", "ルート", "地図", "案内",
                "予約", "荷物", "ビザ", "パスポート", "観光", "ガイド", "宿泊", "旅", "冒険", "遠足", "巡礼"
            )
        ),
        CategoryRule(
            CATEGORY_ECONOMY,
            "Ekonomia",
            "Economy",
            setOf(
                "economy", "economic", "economics", "finance", "financial", "bank", "banking", "inflation", "deflation", "market",
                "investment", "invest", "investor", "stock", "bond", "share", "equity", "portfolio", "trading", "trader",
                "tax", "taxation", "tariff", "duty", "levy", "toll", "budget", "fiscal", "deficit", "surplus",
                "loan", "lending", "lender", "borrower", "interest", "interest rate", "mortgage", "credit", "credit card",
                "currency", "money", "cash", "coin", "bill", "cheque", "check", "credit", "debit", "transfer", "remittance",
                "profit", "loss", "revenue", "income", "expense", "cost", "price", "fee", "charge", "rate", "discount",
                "capital", "asset", "liability", "equity", "balance sheet", "cash flow", "debt", "debt ceiling", "default",
                "trade", "export", "import", "goods", "commodity", "service", "wholesale", "retail", "commerce", "business",
                "commercial", "industry", "industrial", "manufacture", "manufacturing", "production", "productivity",
                "supply", "demand", "consumer", "customer", "producer", "seller", "buyer", "vendor", "bargain", "negotiate",
                "sale", "purchase", "transaction", "deal", "contract", "agreement", "merger", "acquisition", "buyout",
                "invoice", "receipt", "bill", "account", "accounting", "accountant", "auditor", "audit", "record", "filing",
                "pension", "pension fund", "insurance", "insurance policy", "claim", "coverage", "premium", "deductible",
                "dividend", "earning", "earnings", "profit margin", "roi", "return", "yield", "gross", "net", "salary", "wage",
                "ekonomia", "finanse", "bank", "bankowy", "inwestycja", "podatek", "rynek", "pieniadze", "zysk", "kredyt",
                "handel", "import", "eksport", "biznes", "przemysl", "usluga", "produkt", "kupiec", "sprzedawca", "transakcja",
                "rachunkowość", "umowa", "faktura", "kurs", "wymiana", "dochód", "wydatek", "kapitał", "drażg", "ubezpieczenie",
                "経済", "経済学", "金融", "銀行", "投資", "株", "債券", "取引", "税", "商業", "貿易", "輸出", "輸入",
                "商品", "サービス", "消費者", "利益", "損失", "収入", "支出", "資本", "負債", "融資", "保険", "給与"
            )
        ),
        CategoryRule(
            CATEGORY_WORK,
            "Praca",
            "Work",
            setOf(
                "work", "job", "career", "occupation", "profession", "employment", "employer", "employee", "staff", "workforce",
                "office", "workplace", "company", "corporation", "firm", "enterprise", "organization", "department", "division",
                "business", "industry", "sector", "market", "startup", "multinational", "subsidiary", "branch", "headquarters",
                "manager", "director", "executive", "chief executive", "ceo", "supervisor", "boss", "manager", "lead", "lead",
                "employee", "intern", "apprentice", "contractor", "freelancer", "consultant", "analyst", "specialist", "expert",
                "engineer", "architect", "designer", "developer", "programmer", "coder", "accountant", "auditor", "lawyer",
                "doctor", "nurse", "teacher", "professor", "instructor", "coach", "trainer", "consultant", "adviser", "advisor",
                "sales", "salesman", "representative", "manager", "marketing", "customer service", "receptionist", "secretary",
                "administrative", "financial", "human resources", "operations", "quality assurance", "research", "development",
                "meeting", "conference", "seminar", "workshop", "training", "presentation", "webinar", "summit", "convention",
                "project", "mission", "task", "assignment", "goal", "objective", "deadline", "milestone", "deliverable",
                "responsibility", "duty", "obligation", "requirement", "accountability", "performance", "evaluation", "review",
                "skill", "ability", "competence", "expertise", "knowledge", "experience", "qualification", "credential",
                "achievement", "success", "accomplishment", "failure", "challenge", "difficulty", "obstacle", "problem",
                "promotion", "raise", "salary", "wages", "benefits", "bonus", "incentive", "compensation", "package",
                "recruitment", "hiring", "hire", "recruit", "interview", "resume", "cv", "application", "candidate",
                "promotion", "demotion", "transfer", "reassignment", "relocation", "termination", "resign", "resignation",
                "retire", "retirement", "pension", "severance", "layoff", "firing", "dismissal", "suspension",
                "overtime", "shift", "schedule", "hours", "full-time", "part-time", "contract", "temporary", "permanent",
                "vacation", "leave", "sick leave", "maternity leave", "sabbatical", "break", "rest", "time off",
                "desk", "office", "cubicle", "conference room", "boardroom", "break room", "cafeteria", "parking",
                "computer", "phone", "email", "document", "file", "report", "memo", "note", "spreadsheet", "database",
                "communication", "collaboration", "teamwork", "cooperation", "networking", "relationship", "connection",
                "conflict", "dispute", "grievance", "complaint", "resolution", "mediation", "negotiation", "compromise",
                "praca", "pracownik", "zatrudnienie", "kariera", "zawód", "pracownik", "personel", "zespół", "pracownicy",
                "biuro", "firma", "korporacja", "przedsiębiorstwo", "organizacja", "dział", "oddział", "centrum",
                "dyrektor", "kierownik", "szef", "menadżer", "przełożony", "супер wisor", "asystent", "doradca",
                "inżynier", "projektant", "programista", "analityk", "konsultant", "specjalista", "eksperyment",
                "spotkanie", "konferencja", "seminarium", "warsztacie", "szkolenie", "prezentacja", "seminarium",
                "projekt", "zadanie", "cel", "termin", "odpowiedzialność", "obowiązek", "wydajność", "ocena",
                "umiejętność", "kompetencja", "doświadczenie", "kwalifikacja", "sukces", "wyzwanie", "problem",
                "awans", "podwyżka", "pensja", "zarobki", "świadczenia", "bonus", "odszkodowanie", "pakiet",
                "rekrutacja", "zatrudnianie", "wywiad", "kandydat", "cv", "umowa", "urlop", "rezygnacja",
                "仕事", "仕事", "職業", "雇用", "雇雇用", "職人", "スタッフ", "労働力", "労働力",
                "オフィス", "職場", "会社", "企業", "組織", "部門", "部門", "支部", "本社",
                "マネージャー", "ディレクター", "エグゼクティブ", "ceo", "スーパーバイザー", "ボス", "リード",
                "従業員", "インターン", "アプレンティス", "請負業者", "フリーランサー", "コンサルタント", "アナリスト"
            )
        ),
        CategoryRule(
            CATEGORY_EDUCATION,
            "Edukacja",
            "Education",
            setOf(
                "school", "study", "learn", "education", "educational", "student", "pupil", "learner", "scholar",
                "teacher", "instructor", "professor", "lecturer", "tutor", "educator", "pedagogy", "academic",
                "class", "classroom", "lesson", "course", "curriculum", "syllabus", "training", "instruction",
                "exam", "test", "quiz", "assessment", "evaluation", "grading", "score", "mark", "grade", "gpa",
                "university", "college", "school", "academy", "institute", "elementary", "middle", "high school",
                "primary", "secondary", "tertiary", "graduate", "postgraduate", "undergraduate", "higher education",
                "homework", "assignment", "project", "essay", "paper", "thesis", "dissertation", "research",
                "dictionary", "encyclopedia", "textbook", "workbook", "reader", "handbook", "manual", "guide",
                "grammar", "syntax", "vocabulary", "word", "phrase", "sentence", "paragraph", "composition",
                "subject", "discipline", "field", "major", "minor", "specialization", "concentration",
                "mathematics", "math", "arithmetic", "algebra", "geometry", "calculus", "trigonometry", "statistics",
                "science", "chemistry", "physics", "biology", "earth science", "anatomy", "physiology", "botany", "zoology",
                "history", "ancient", "medieval", "modern", "contemporary", "geography", "world", "continent", "country",
                "language", "english", "japanese", "spanish", "french", "german", "chinese", "korean", "italian", "portuguese",
                "literature", "novel", "poem", "poetry", "short story", "play", "drama", "fiction", "nonfiction", "essay",
                "writing", "reading", "comprehension", "speaking", "listening", "conversation", "dialogue", "discussion",
                "verb", "noun", "adjective", "adverb", "preposition", "conjunction", "article", "pronoun", "tense",
                "present", "past", "future", "conditional", "subjunctive", "imperative", "active", "passive",
                "book", "bookshelf", "library", "campus", "dormitory", "dorm", "cafeteria", "lecture hall",
                "whiteboard", "blackboard", "chalkboard", "smartboard", "projector", "screen", "desktop", "computer",
                "chalk", "marker", "pen", "pencil", "eraser", "notebook", "paper", "folder", "binder", "organizer",
                "degree", "diploma", "certificate", "credential", "license", "qualification", "competency",
                "bachelor", "master", "doctorate", "phd", "associate", "graduate", "postgraduate", "alumni", "alumna",
                "class", "grade", "level", "year", "semester", "term", "quarter", "session", "academic year",
                "pass", "fail", "pass", "succeed", "struggle", "progress", "regression", "improvement", "achievement",
                "scholarship", "grant", "loan", "tuition", "fee", "cost", "financial aid", "sponsorship",
                "research", "experiment", "lab", "laboratory", "field study", "internship", "practicum", "observation",
                "edukacja", "nauczanie", "nauczyciel", "uczeń", "student", "szkoła", "uniwersytet", "akademia",
                "clase", "lekcja", "kurs", "przedmiot", "matematyka", "nauka", "język", "literatura", "historia",
                "geografia", "gramatyka", "słownictwo", "zdanie", "esej", "praca", "egzamin", "test", "ocena",
                "stopień", "dyplom", "certyfikat", "kwalifikacja", "licencja", "badania", "eksperyment", "laboratorium",
                "教育", "教育", "学校", "学生", "学生", "教師", "講師", "教授", "大学", "学習", "研究",
                "クラス", "教室", "レッスン", "コース", "カリキュラム", "試験", "テスト", "クイズ", "評価", "スコア", "成績",
                "数学", "科学", "化学", "物理学", "生物学", "歴史", "地理", "言語", "文学", "文法", "語彙"
            )
        ),
        CategoryRule(
            CATEGORY_HEALTH,
            "Zdrowie",
            "Health",
            setOf(
                "health", "healthy", "unhealthy", "disease", "illness", "sickness", "ailment", "disorder", "condition",
                "medical", "medicine", "medication", "drug", "pharmaceutical", "treatment", "therapy", "cure", "remedy",
                "doctor", "physician", "practitioner", "surgeon", "specialist", "dentist", "nurse", "therapist", "therapist",
                "hospital", "clinic", "medical center", "emergency room", "er", "icu", "surgery", "ward", "bed",
                "patient", "examination", "checkup", "diagnosis", "prognosis", "symptom", "syndrome", "complication",
                "pain", "ache", "hurt", "suffer", "injury", "wound", "cut", "bruise", "fracture", "burn", "sprain",
                "infection", "virus", "bacterial", "fungal", "parasitic", "contagious", "epidemic", "pandemic", "outbreak",
                "fever", "cough", "cold", "flu", "influenza", "covid", "pneumonia", "bronchitis", "asthma", "allergy",
                "heart", "cardiac", "coronary", "artery", "vein", "blood", "blood pressure", "cholesterol", "diabetes",
                "cancer", "tumor", "carcinoma", "malignant", "benign", "chemotherapy", "radiation", "oncology",
                "depression", "anxiety", "stress", "mental", "psychiatric", "psychology", "psychotherapy", "counseling",
                "sleep", "insomnia", "nightmare", "sleepwalking", "narcolepsy", "apnea", "restless", "fatigue",
                "diet", "nutrition", "nutritious", "vitamins", "minerals", "protein", "carbohydrate", "fat", "calorie",
                "exercise", "fitness", "workout", "training", "running", "swimming", "yoga", "pilates", "aerobic", "strength",
                "weight", "obesity", "overweight", "underweight", "bmi", "metabolic", "metabolism", "calorie burn",
                "blood test", "xray", "ultrasound", "ct scan", "mri", "scan", "imaging", "endoscopy", "biopsy",
                "surgery", "operation", "surgical", "anesthesia", "anesthetic", "operating room", "surgeon", "surgeon",
                "vaccine", "vaccination", "immunize", "immunity", "antibody", "immune system", "immunization", "booster",
                "prescription", "prescription drug", "dose", "dosage", "pill", "tablet", "capsule", "injection", "iv",
                "side effect", "adverse", "allergy", "allergic", "allergen", "anaphylaxis", "reaction", "sensitivity",
                "rehabilitation", "physical therapy", "pt", "recovery", "convalescence", "healing", "healing process",
                "emergency", "first aid", "cpr", "resuscitation", "ambulance", "paramedic", "trauma", "critical",
                "chronic", "acute", "temporary", "permanent", "remission", "relapse", "progression", "deterioration",
                "zdrowie", "zdrowł", "choroba", "ilość", "zaburzenie", "medycyna", "leczenie", "terpia", "lek",
                "lekarz", "szpital", "klinika", "pacjent", "badanie", "diagnostyka", "symptom", "zespół", "powikłania",
                "ból", "obolał", "uraz", "rany", "złamanie", "oparzenie", "skręcenie", "infekcja", "wirus",
                "gorączka", "kaszel", "przeziębienie", "grypa", "alergia", "serce", "ciśnienie krwi", "cukrzyca",
                "nowotwór", "zapalenie", "ćwiczenie", "dieta", "waga", "umięśnienie", "zmęczenie", "sen",
                "健康", "健康", "病気", "疾患", "病気", "医学", "治療", "医学", "薬", "医者", "医師", "外科医",
                "看護師", "セラピスト", "病院", "診療所", "患者", "検査", "診断", "症状", "症候群", "合併症",
                "痛み", "怪我", "切り傷", "青紫色", "骨折", "火傷", "捻挫", "感染", "ウイルス", "細菌",
                "熱", "咳", "風邪", "インフルエンザ", "アレルギー", "心臓", "血圧", "糖尿病", "がん", "腫瘍",
                "運動", "フィットネス", "トレーニング", "ランニング", "スイミング", "ヨガ", "ダイエット", "栄養", "睡眠"
            )
        ),
        CategoryRule(
            CATEGORY_TECHNOLOGY,
            "Technologia",
            "Technology",
            setOf(
                "technology", "tech", "digital", "electronic", "electric", "innovation", "invention", "gadget",
                "computer", "laptop", "desktop", "pc", "mac", "tablet", "ipad", "smartphone", "mobile phone", "phone",
                "hardware", "processor", "cpu", "gpu", "motherboard", "ram", "memory", "storage", "hard drive", "ssd",
                "software", "program", "application", "app", "system", "operating system", "windows", "mac", "linux",
                "internet", "web", "website", "url", "browser", "search", "search engine", "google", "firefox",
                "network", "networking", "connection", "wifi", "wireless", "broadband", "bandwidth", "router", "modem",
                "database", "data", "big data", "analytics", "analysis", "data science", "machine learning", "ai", "artificial intelligence",
                "code", "programming", "programmer", "coder", "developer", "development", "software development", "web development",
                "java", "python", "javascript", "c++", "c#", "ruby", "php", "golang", "rust", "typescript",
                "algorithm", "data structure", "logic", "boolean", "variable", "function", "class", "method", "object",
                "robot", "robotics", "automation", "automated", "automate", "autonomous", "robot arm", "drone",
                "security", "cybersecurity", "cyber", "hacker", "hack", "encryption", "encrypted", "password", "username",
                "firewall", "antivirus", "malware", "virus", "trojan", "worm", "spyware", "ransomware", "phishing",
                "email", "email client", "gmail", "outlook", "attachment", "inbox", "spam", "filter",
                "download", "upload", "transfer", "sync", "synchronize", "backup", "restore", "cloud", "cloud storage",
                "gaming", "video game", "game console", "playstation", "xbox", "nintendo", "vr", "virtual reality", "ar",
                "screen", "display", "monitor", "resolution", "pixel", "refresh rate", "hz", "color", "brightness",
                "keyboard", "mouse", "touchpad", "trackball", "joystick", "gamepad", "controller", "headset", "speaker",
                "camera", "webcam", "microphone", "mic", "audio", "video", "streaming", "stream", "broadcast",
                "battery", "charger", "power", "electricity", "voltage", "amp", "watt", "solar", "renewable",
                "bluetooth", "usb", "hdmi", "ethernet", "jack", "port", "cable", "adapter", "converter",
                "update", "upgrade", "patch", "version", "release", "beta", "alpha", "stable", "unstable",
                "error", "bug", "glitch", "crash", "freeze", "lag", "delay", "latency", "bandwidth", "throughput",
                "technologia", "komputer", "aplikacja", "internet", "siec", "dane", "robot", "cyfrowy", "telefon",
                "laptop", "tablet", "ekran", "plik", "folder", "blad", "wirus", "kopie", "aktualizacja", "sieć",
                "urządzenie", "programowanie", "programista", "oprogramowanie", "system", "procesor", "pamięć",
                "technologie", "digitale", "elektroniczne", "sieć", "kod", "algorytm", "bezpieczeństwo", "szyfrowanie",
                "技術", "テクノロジー", "デジタル", "コンピュータ", "ノートパソコン", "タブレット", "スマートフォン",
                "インターネット", "ウェブサイト", "ネットワーク", "ソフトウェア", "ハードウェア", "プロセッサ", "メモリ",
                "ストレージ", "データベース", "データ", "人工知能", "機械学習", "ロボット", "ドローン", "サイバーセキュリティ",
                "パスワード", "ユーザー名", "ダウンロード", "アップロード", "クラウド", "ビデオゲーム", "ゲーム"
            )
        ),
        CategoryRule(
            CATEGORY_SHOPPING,
            "Zakupy",
            "Shopping",
            setOf(
                "shop", "shopping", "store", "retail", "shopper", "mall", "market", "marketplace", "vendor",
                "buy", "sell", "purchase", "sale", "selling", "customer", "buyer", "seller", "merchant", "trader",
                "price", "cost", "fee", "rate", "expense", "budget", "spending", "spend", "afford", "expensive", "cheap",
                "discount", "discounted", "sale price", "markup", "margin", "profit", "loss", "clearance", "bargain",
                "coupon", "voucher", "coupon code", "promotion", "offer", "deal", "special", "offer", "advertise", "advertisement",
                "payment", "pay", "payable", "cash", "card", "credit card", "debit card", "check", "bank transfer", "online payment",
                "receipt", "receipt", "invoice", "bill", "reciept", "transaction", "order", "purchase order", "confirmation",
                "product", "item", "merchandise", "commodity", "goods", "ware", "product line", "brand", "label", "brand name",
                "quality", "quality control", "defect", "warranty", "guarantee", "return", "refund", "exchange", "replacement",
                "size", "fitting", "fit", "measurement", "measurement", "size chart", "material", "color", "style", "design",
                "clothing", "apparel", "fashion", "wear", "garment", "dress", "shirt", "blouse", "pants", "trousers", "jeans",
                "skirt", "shorts", "coat", "jacket", "blazer", "sweater", "sweater", "hoodie", "vest", "cardigan", "cardigan",
                "shoes", "footwear", "boot", "sneaker", "sandal", "flip-flop", "slipper", "loafer", "heel", "heel", "pump",
                "hat", "cap", "beanie", "scarf", "glove", "mitten", "belt", "bag", "purse", "wallet", "backpack", "handbag",
                "jewelry", "jewel", "necklace", "bracelet", "anklet", "ring", "earring", "pendant", "brooch", "locket",
                "watch", "timepiece", "clock", "watch face", "band", "strap", "link", "buckle",
                "accessory", "accessories", "ornament", "trim", "button", "zipper", "pocket", "sleeve", "collar", "hem",
                "furniture", "home furniture", "furniture set", "sofa", "couch", "loveseat", "chair", "armchair", "recliner",
                "table", "coffee table", "dining table", "desk", "side table", "nightstand", "end table", "console",
                "bed", "mattress", "frame", "headboard", "footboard", "sheets", "comforter", "pillow", "pillowcase",
                "cabinet", "cabinet", "shelf", "shelving", "shelves", "bookcase", "bookshelf", "closet", "wardrobe",
                "drawer", "dresser", "bureau", "chest", "trunk", "ottoman", "storage", "storage chest", "storage box",
                "decoration", "decor", "wall art", "picture", "painting", "poster", "mirror", "frame", "vase", "lamp",
                "lighting", "light", "bulb", "chandelier", "sconce", "fixture", "lamp", "table lamp", "floor lamp",
                "window treatment", "curtain", "drape", "blinds", "shutters", "shade", "valance", "swag", "rod",
                "rug", "carpet", "mat", "area rug", "runner", "throw rug", "doormatt",
                "kitchen", "kitchen appliance", "stove", "oven", "range", "cooktop", "microwave", "dishwasher", "refrigerator",
                "fridge", "freezer", "blender", "food processor", "mixer", "coffee maker", "kettle", "toaster",
                "grocery", "grocery store", "supermarket", "grocery shopping", "food shopping", "shopping cart", "shopping basket",
                "checkout", "cashier", "register", "point of sale", "pos", "barcode", "scanner", "bag", "paper bag", "plastic bag",
                "delivery", "shipping", "expedited", "standard", "tracking", "tracking number", "order tracking",
                "return policy", "return window", "restocking fee", "warranty", "warranty period", "extended warranty",
                "zakupy", "kupic", "sprzedać", "cena", "rabat", "klient", "sklep", "towar", "produkt", "rozmiar",
                "ubranie", "koszula", "spodnie", "but", "kapelusz", "kurtka", "plecak", "torebka", "portfel", "biżuteria",
                "meble", "stół", "krzeslo", "łóżko", "szafa", "lampa", "obraz", "lustro", "dywan", "zasłony",
                "Buy", "sell", "purchase", "sale", "customer", "cost", "price", "discount", "promotion", "quality",
                "買", "売", "値段", "店", "支払い", "割引", "客", "商品", "服", "色", "サイズ", "品質",
                "ドレス", "シャツ", "ズボン", "靴", "帽子", "ジャケット", "セーター", "バッグ", "財布", "配送",
                "店舗", "小売", "マーケットプレイス", "価格", "コスト", "割引", "クーポン", "プロモーション", "オファー",
                "支払い", "レシート", "請求書", "商品", "品質", "保証", "返品", "交換", "配送", "お客様"
            )
        ),
        CategoryRule(
            CATEGORY_HOME_DAILY,
            "Dom i codziennosc",
            "Home & daily life",
            setOf(
                "home", "house", "apartment", "flat", "condo", "place", "residence", "dwelling", "lodging", "accommodations",
                "room", "bedroom", "living room", "sitting room", "lounge", "family room", "den", "study", "office",
                "kitchen", "kitchenette", "dining room", "eat-in kitchen", "breakfast room", "breakfast nook",
                "bathroom", "restroom", "washroom", "toilet", "shower", "bathtub", "tub", "basin", "sink", "toilet",
                "hallway", "corridor", "foyer", "entryway", "entrance", "exit", "stairway", "stair", "stairs", "steps",
                "basement", "cellar", "attic", "loft", "storage", "garage", "carport", "porch", "patio", "deck", "balcony",
                "door", "doorway", "door frame", "door knob", "door handle", "key", "lock", "bolt", "latch",
                "window", "window pane", "window frame", "sill", "frame", "screen", "shutter", "blind", "shade",
                "wall", "wall color", "wallpaper", "wall covering", "paint", "painted", "paneling", "wainscoting",
                "floor", "flooring", "tile", "wood floor", "carpet", "rug", "mat", "hardwood", "laminate", "linoleum",
                "ceiling", "ceiling fan", "ceiling light", "plaster", "drywall", "crown molding", "texture",
                "furniture", "sofa", "couch", "chair", "armchair", "recliner", "loveseat", "sectional", "ottoman",
                "table", "coffee table", "end table", "side table", "dining table", "desk", "nightstand", "console",
                "bed", "mattress", "frame", "headboard", "footboard", "box spring", "pillow", "pillowcase", "sheet",
                "wardrobe", "closet", "cabinet", "cabinet", "shelf", "dresser", "bureau", "chest", "chest of drawers",
                "lamp", "lighting", "light", "light bulb", "floor lamp", "table lamp", "ceiling light", "chandelier",
                "Decoration", "decor", "wall art", "picture", "painting", "poster", "frame", "mirror", "vase", "ornament",
                "cleaning", "clean", "cleanliness", "hygiene", "sanitary", "sanitize", "disinfect", "disinfectant",
                "dust", "dusting", "vacuum", "sweeping", "broom", "mop", "wet mop", "sweep", "brush", "dustpan",
                "laundry", "wash", "washing", "wash cycle", "spin cycle", "rinse", "dry", "drying", "fold", "folding",
                "detergent", "soap", "cleaning solution", "bleach", "softener", "fabric softener", "static", "wrinkle",
                "ironing", "iron", "iron clothes", "wrinkles", "steam", "steam iron", "hang", "hanging", "clothesline",
                "cooking", "cook", "prepare", "preparation", "recipe", "ingredient", "mixing", "mixing bowl", "pot", "pan",
                "eating", "eat", "meal", "breakfast", "lunch", "dinner", "snack", "nibble", "bite", "taste", "flavor",
                "sleeping", "sleep", "bed", "alarm", "alarm clock", "wake", "alarm", "bedtime", "sleeptime", "rest",
                "waking", "alarm", "awake", "up", "get up", "rise", "rise and shine", "morning", "routine",
                "getting dressed", "dress", "clothing", "get dressed", "outfit", "wardrobe", "clothes", "garment",
                "bathing", "bath", "bathroom", "shower", "shower", "tub", "bathe", "soak", "washcloth", "towel",
                "brushing teeth", "toothbrush", "toothpaste", "floss", "mouthwash", "dentist", "dental", "cavity",
                "grooming", "comb", "brush", "hair", "hairbrush", "haircut", "shaving", "razor", "shaving cream",
                "morning routine", "evening routine", "bedtime routine", "ritual", "habits", "schedule", "daily schedule",
                "shopping", "grocery shopping", "store", "market", "grocery", "meats", "produce", "dairy", "bread",
                "cooking", "kitchen", "stove", "oven", "microwave", "blender", "mixer", "measuring", "preparation",
                "trash", "garbage", "trash can", "trash bag", "garbage disposal", "waste", "compost", "recycling",
                "garden", "gardening", "yard", "lawn", "grass", "mowing", "watering", "plant", "flower", "tree",
                "dom", "domek", "mieszkanie", "apartament", "dom", "pokoj", "sypialnia", "salon", "kuchnia", "lazienka",
                "korytarz", "schody", "drzwi", "okno", "ściana", "podłoga", "sufit", "dywan", "zaslony", "lampа",
                "czyszczenie", "pranie", "gotowanie", "spanie", "budzenie", "ubieranie", "mycie", "prysznic", "kąpiel",
                "家", "家庭", "日常", "部屋", "朝", "夜", "車", "アパート", "引っ越し", "庭", "寝室", "リビング",
                "台所", "風呂", "トイレ", "扉", "窓", "壁", "床", "天井", "照明", "家具", "植物",
                "毎日", "日常", "生活", "日常生活", "家", "屋", "部屋", "台所", "バスルーム", "トイレ"
            )
        ),
        CategoryRule(
            CATEGORY_CULTURE,
            "Kultura i rozrywka",
            "Culture & entertainment",
            setOf(
                "culture", "cultural", "art", "artistic", "artist", "artistry", "creativity", "creative",
                "music", "musical", "musician", "song", "singing", "singer", "vocal", "vocalist", "instrument", "instrumental",
                "piano", "keyboard", "organ", "guitar", "electric guitar", "acoustic guitar", "bass", "drums", "percussion",
                "violin", "viola", "cello", "trumpet", "trombone", "tuba", "clarinet", "saxophone", "sax", "flute",
                "band", "orchestra", "ensemble", "conductor", "composer", "conductor", "maestro", "arrangement", "harmony",
                "movie", "film", "cinema", "cinema", "theatre", "theater", "screen", "screen", "premiere", "release",
                "actor", "actress", "cast", "role", "character", "protagonist", "antagonist", "supporting", "lead role",
                "director", "director", "producer", "screenwriter", "writer", "script", "screenplay", "dialogue", "narration",
                "scene", "scene", "setting", "location", "backdrop", "scenery", "set", "props", "costume", "makeup",
                "plot", "storyline", "narrative", "arc", "climax", "resolution", "ending", "final", "twist", "cliffhanger",
                "genre", "comedy", "tragedy", "drama", "thriller", "mystery", "horror", "action", "adventure", "fantasy",
                "romance", "romantic", "love story", "science fiction", "sci-fi", "animation", "animated", "anime",
                "book", "novel", "fiction", "nonfiction", "short story", "tale", "fable", "fairy tale", "adventure",
                "poem", "poetry", "poet", "verse", "prose", "poetry", "literary", "literature", "manuscript",
                "author", "writer", "novelist", "playwright", "poet", "essayist", "journalist", "reporter", "columnist",
                "essay", "article", "essay", "composition", "paper", "research paper", "study", "article", "piece",
                "theatre", "theater", "play", "performance", "production", "show", "act", "scene", "stage", "stage",
                "sport", "sports", "athletic", "athlete", "competition", "compete", "tournament", "match", "game",
                "soccer", "football", "american football", "basketball", "baseball", "tennis", "golf", "hockey",
                "swimming", "diving", "water sports", "track", "field", "running", "sprinting", "maratho", "relay",
                "boxing", "wrestling", "martial arts", "karate", "taekwondo", "judo", "kung fu", "muay thai", "kickboxing",
                "gymnastics", "acrobatics", "parkour", "skateboarding", "snowboarding", "skiing", "ice skating", "roller skating",
                "hobby", "collecting", "collection", "collector", "hobbies", "pastime", "leisure", "recreation",
                "gaming", "video game", "game", "video game", "game console", "playstation", "xbox", "nintendo",
                "pc gaming", "computer game", "arcade", "board game", "tabletop", "chess", "card game", "poker",
                "vr", "virtual reality", "ar", "augmented reality", "immersive", "3d", "graphics", "gameplay",
                "photography", "photographer", "photo", "picture", "picture", "image", "snapshot", "portrait", "landscape",
                "video", "video", "videography", "filmmaker", "filming", "shooting", "camera", "camcorder", "frame",
                "art", "painting", "painter", "draw", "drawing", "sketch", "illustration", "illustration", "cartoon",
                "sculpture", "sculptor", "statue", "sculpture", "carving", "relief", "bust", "figure", "figurine",
                "crafts", "craft", "handmade", "handicraft", "diy", "do-it-yourself", "maker", "making", "creation",
                "museum", "gallery", "art gallery", "exhibit", "exhibition", "exhibition", "display", "showcase", "exhibition",
                "festival", "festival", "celebration", "celebration", "event", "concert", "concert", "performance",
                "kultura", "sztuka", "artystа", "muzyka", "muzykant", "piosenka", "piosenkarz", "instrument", "orkiestra",
                "film", "kino", "teatr", "aktor", "reżyser", "producent", "sceariusz", "postać", "scena", "plot",
                "gatunek", "komedia", "dramat", "horror", "akcja", "przygoda", "fantazja", "romantyka", "sci-fi",
                "książka", "powieść", "historia", "opowieść", "poemat", "poeta", "pisarz", "autor", "esej",
                "sport", "piłka nożna", "koszykówka", "tenis", "golf", "pływanie", "boks", "sztuki walki",
                "gra", "gra wideo", "konsola", "gra planszowa", "gra karciana", "szachy", "poker",
                "fotografia", "fotograf", "zdjęcie", "obraz", "malarstwo", "rysunek", "rzeźba", "rzeźbiarz",
                "rękodzieło", "DIY", "tworzenie", "galeria", "muzeum", "wystawa", "festiwal", "koncert", "show",
                "文化", "美術", "アート", "アーティスト", "音楽", "音楽家", "歌", "歌手", "楽器", "オーケストラ",
                "映画", "映画", "演劇", "劇場", "俳優", "女優", "監督", "プロデューサー", "脚本家", "キャスト",
                "ジャンル", "コメディ", "ドラマ", "ホラー", "アクション", "冒険", "ファンタジー", "ロマンス", "SF",
                "本", "小説", "執筆", "短編", "詩", "詩人", "作家", "作者", "エッセイ", "記事",
                "スポーツ", "サッカー", "バスケットボール", "テニス", "ゴルフ", "野球", "ボクシング", "格闘技",
                "ゲーム", "ビデオゲーム", "ゲーム機", "ボードゲーム", "チェス", "トランプゲーム", "ポーカー"
            )
        ),
        CategoryRule(
            CATEGORY_RELATIONSHIPS,
            "Relacje",
            "Relationships",
            setOf(
                "relationship", "relationship", "bond", "connection", "connection", "social", "social bond",
                "friend", "friendship", "best friend", "bff", "buddy", "companion", "comrade", "pal", "mate",
                "acquaintance", "colleague", "coworker", "neighbor", "neighbor", "stranger", "outsider", "foreigner",
                "family", "family member", "relative", "relation", "kinship", "kin", "clan", "household",
                "parent", "father", "dad", "daddy", "papa", "mother", "mom", "mommy", "mama", "mum",
                "son", "boy", "daughter", "girl", "child", "kid", "baby", "infant", "toddler",
                "brother", "bro", "big brother", "little brother", "sister", "sis", "big sister", "little sister", "sibling",
                "grandparent", "grandfather", "grandpa", "grandpa", "grandmother", "grandma", "granny",
                "uncle", "aunt", "auntie", "auntie", "cousin", "cousin", "male cousin", "female cousin",
                "nephew", "niece", "cousin", "godparent", "godfather", "godmother", "godchild", "godson", "goddaughter",
                "in-law", "mother-in-law", "father-in-law", "sister-in-law", "brother-in-law", "son-in-law", "daughter-in-law",
                "spouse", "husband", "wife", "husband", "wife", "partner", "roommate", "flatmate", "housemate",
                "boyfriend", "girlfriend", "lover", "lover", "significant other", "so", "sweetheart", "darling",
                "engagement", "engaged", "fiancée", "fiancé", "betrothal", "proposal", "propose", "propose",
                "marriage", "marry", "married", "matrimony", "wedding", "wedding ceremony", "bride", "groom",
                "divorce", "divorced", "separation", "separated", "ex", "ex-husband", "ex-wife", "ex-partner",
                "love", "beloved", "dear", "dearest", "affection", "affectionate", "tenderness", "tenderhearted",
                "like", "care", "caring", "concern", "compassion", "empathy", "sympathy", "understanding",
                "protect", "protection", "defend", "defending", "safeguard", "care for", "look after", "care for",
                "support", "support", "encourage", "encouragement", "comfort", "console", "comfort",
                "listen", "listening", "hear", "understand", "understanding", "comprehend", "appreciate",
                "trust", "trustworthy", "reliable", "dependable", "loyal", "loyalty", "faithful", "faith",
                "emotion", "emotional", "feeling", "feeling", "sentiment", "sentimentality", "passion", "passionate",
                "happy", "happiness", "joy", "joyful", "cheerful", "merry", "glad", "glad", "delighted",
                "sad", "sadness", "sorrow", "sorrowful", "unhappy", "mourn", "mourning", "grieve", "grief",
                "angry", "anger", "rage", "fury", "furious", "irate", "irritated", "irritation", "annoyed",
                "excited", "excitement", "exhilaration", "enthusiasm", "enthusiastic", "thrilled", "thrilled",
                "nervous", "nervousness", "anxiety", "anxious", "apprehension", "apprehensive", "worried",
                "scared", "fear", "frightened", "frightening", "terrified", "horror", "dread", "terror",
                "jealous", "jealousy", "envious", "envy", "resentment", "resentful", "bitter", "bitterness",
                "proud", "pride", "prideful", "arrogant", "conceited", "vanity", "vain", "bragging",
                "shame", "shameful", "ashamed", "humiliation", "humiliated", "humiliating", "embarrassing",
                "embarrassment", "embarrassed", "mortification", "mortified", "chagrin",
                "kiss", "kissing", "kiss", "peck", "smooch", "mouth", "lips", "lip kiss",
                "hug", "hug", "embrace", "embracing", "cuddle", "cuddling", "snuggle", "snuggling", "hold",
                "hold", "holding", "hold hands", "interlock", "clasp", "grip", "squeeze", "embrace",
                "touch", "touching", "caress", "caressing", "stroke", "stroking", "pet", "petting",
                "smile", "smiling", "smile", "grin", "grinning", "laugh", "laughing", "chuckle", "giggle",
                "cry", "crying", "cry", "shed tears", "weep", "weeping", "tear", "tears", "tear drop",
                "scream", "screaming", "scream", "shriek", "shrieking", "yell", "yelling", "yell", "shout",
                "whisper", "whispered", "whisper", "murmur", "murmuring", "soft voice", "lowered voice", "hushed",
                "conversation", "talk", "talking", "speak", "speaking", "discussion", "discuss", "dialogue", "dialog",
                "argument", "argue", "arguing", "quarrel", "quarrel", "quarreling", "bickering", "bicker", "dispute",
                "disagreement", "disagree", "discord", "discordant", "conflict", "conflicted", "at odds", "odds",
                "compromise", "compromise", "understand", "understanding", "agreement", "agree", "agreed",
                "reconciliation", "reconcile", "reconciling", "make up", "make peace", "peace", "peace",
                "apology", "apologize", "apology", "sorry", "remorse", "regret", "repent", "regretful",
                "forgive", "forgiveness", "forgive", "pardon", "pardon", "absolution", "absolve", "let go",
                "relacja", "przyjaznesc", "przyjaciel", "przyjaciel", "kolega", "sasiad", "obca osoba", "nieznajomy",
                "rodzina", "rodzin", "krewny", "pokrewienstwo", "rodzinne", "dom", "gospodarstwo",
                "rodzic", "ojciec", "mama", "matka", "matka", "syn", "corka", "dziecko", "niemowle",
                "brat", "siostra", "brat starszy", "siostra starsza", "brat mlodszy", "siostra mladsza", "rodzenstwo",
                "dziadek", "babcia", "dziadkowie", "wujek", "ciotka", "kuzyn", "kuzyna", "bratanec",
                "bratanica", "siostrzeniec", "siostrzenica", "malzenstwo", "maz", "zona", "mezczyzna",
                "kobieta", "narzeczona", "narzeczony", "mlodn para", "zaludnienie",
                "milosc", "kochanek", "kochanie", "czuly", "czulosc", "troska", "troskliwy", "wsparcie",
                "emocja", "uczucie", "sentyment", "pasja", "zapal", "szczes", "smutek", "gniew", "zlość",
                "radosc", "radosny", "wesoly", "zadowolenie", "zadowolony", "smutek", "smutny", "zal",
                "złosć", "zły", "wściekły", "oburzony", "rozgniewany", "drażniony", "podrażniony",
                "nerwowość", "nerowy", "lęk", "strach", "przerażenie", "zastraszenie", "zastraszajacy",
                "zazdrosc", "zazdrosny", "zazdrość", "zawisć", "zawistny", "urazony", "resentment",
                "duma", "dumny", "pycha", "arogancja", "zarozumiale", "samouwielbienie", "zwycistwo",
                "wstydu", "wstyd", "zelazenie", "poniżenie", "poniżony", "upokorzenie", "upokorzony",
                "zawiration", "zawsze", "kochany", "drogi", "zyczliwy", "czuly", "tender", "nażyczliwy",
                "関係", "友情", "友達", "友人", "同僚", "隣人", "知らない人", "見知らぬ人",
                "家族", "家族", "親戚", "続柄", "親", "父", "父親", "お父さん", "母", "お母さん",
                "子供", "息子", "娘", "赤ちゃん", "兄", "弟", "姉", "妹", "兄弟姉妹",
                "祖父", "祖母", "おじさん", "おばさん", "いとこ", "甥", "姪", "配偶者", "夫", "妻",
                "恋人", "ボーイフレンド", "ガールフレンド", "愛する人", "愛", "愛情", "深い愛",
                "感情", "感じる", "気持ち", "感情", "感覚", "情熱", "熱情", "幸福", "悲しみ", "怒り"
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

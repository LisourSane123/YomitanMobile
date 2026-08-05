package com.yomitanmobile.util

/**
 * Built-in JLPT level fallback for dictionaries that don't ship JLPT tags
 * (most JMDict variants except Jitendex). Used by ViewModels when
 * [com.yomitanmobile.data.local.entity.DictionaryEntry.jlptLevel] is 0 — the
 * dictionary's own JLPT data always wins when present.
 *
 * Lookup is strict: a word resolves a level only when its full
 * (expression, reading) pair matches. We deliberately do NOT fall back to
 * reading-only matching for words containing kanji, because homophones
 * (聞く / 効く / 利く / 菊 / 規矩 all read きく) would otherwise inherit each
 * other's levels — turning every kiku-search hit into N5 when only 聞く is.
 *
 * For kana-only entries (the expression itself has no kanji) we allow an
 * expression-only match, since there is no kanji to disambiguate from.
 *
 * Note: the data here is curated and intentionally incomplete. Words missing
 * from the list return 0 (no level shown). For comprehensive coverage the
 * user should import a JLPT-tagged dictionary such as Jitendex.
 */
object JlptVocabulary {

    fun getLevel(expression: String, reading: String): Int {
        val exp = expression.trim()
        val read = reading.trim()
        if (exp.isEmpty() && read.isEmpty()) return 0

        // Treat empty fields as "use the other one" — kana-only entries from the
        // dictionary normally have reading == expression already.
        val effExp = exp.ifEmpty { read }
        val effRead = read.ifEmpty { exp }

        // Strict (expression, reading) lookup — never falls through to a
        // reading-only match, which would falsely promote homophones.
        pairMap[effExp to effRead]?.let { return it }

        // Kana-only words have no kanji to disambiguate, so an expression-only
        // hit is safe here.
        if (effExp.isKanaOnly()) {
            kanaOnlyMap[effExp]?.let { return it }
        }
        return 0
    }

    /**
     * Every curated (expression, reading) pair for one level, easiest level
     * first in the source data. Used by the bulk JLPT deck generator as a
     * floor: whatever the installed dictionaries tag themselves, these words
     * are always considered for the level.
     *
     * The list is deliberately incomplete (see the class docs) — a
     * JLPT-tagged dictionary such as Jitendex carries far more.
     */
    fun wordsForLevel(level: Int): List<Pair<String, String>> {
        val raw = LEVELS_EASIEST_FIRST.firstOrNull { it.second == level }?.first ?: return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split('\t', limit = 2)
                val expression = parts[0]
                val reading = if (parts.size == 2) parts[1] else parts[0]
                expression to reading
            }
            .distinct()
            .toList()
    }

    private fun String.isKanaOnly(): Boolean {
        if (isEmpty()) return false
        return all { c ->
            c.code in 0x3040..0x309F ||  // hiragana
            c.code in 0x30A0..0x30FF ||  // katakana
            c == 'ー' || c == '・' || c == 'ｰ'
        }
    }

    /**
     * (expression, reading) → level. Indexed easiest → hardest with putIfAbsent
     * so a more basic level wins for the rare word that appears at multiple
     * tiers.
     */
    private val pairMap: Map<Pair<String, String>, Int> by lazy {
        val out = HashMap<Pair<String, String>, Int>(2048)
        for ((raw, level) in LEVELS_EASIEST_FIRST) {
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val parts = trimmed.split('\t', limit = 2)
                val expr = parts[0]
                val read = if (parts.size == 2) parts[1] else parts[0]
                out.putIfAbsent(expr to read, level)
            }
        }
        out
    }

    /**
     * For kana-only EXPRESSIONS (e.g. ありがとう). We deliberately do NOT
     * include readings of kanji words here — that's exactly what causes the
     * homophone bug (聞く's reading きく making every kiku-homophone N5).
     */
    private val kanaOnlyMap: Map<String, Int> by lazy {
        val out = HashMap<String, Int>(256)
        for ((raw, level) in LEVELS_EASIEST_FIRST) {
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val parts = trimmed.split('\t', limit = 2)
                val expr = parts[0]
                if (expr.isKanaOnly()) {
                    out.putIfAbsent(expr, level)
                }
            }
        }
        out
    }

    private val LEVELS_EASIEST_FIRST: List<Pair<String, Int>> by lazy {
        listOf(N5_RAW to 5, N4_RAW to 4, N3_RAW to 3, N2_RAW to 2, N1_RAW to 1)
    }

    // Format: expression\treading per line. Kana-only entries: just the reading
    // (which is also the expression in such cases — see lookup logic above).
    private const val N5_RAW = """
食べる	たべる
飲む	のむ
見る	みる
行く	いく
来る	くる
する	する
聞く	きく
話す	はなす
読む	よむ
書く	かく
買う	かう
売る	うる
作る	つくる
入る	はいる
出る	でる
立つ	たつ
座る	すわる
歩く	あるく
走る	はしる
寝る	ねる
起きる	おきる
帰る	かえる
着る	きる
脱ぐ	ぬぐ
住む	すむ
働く	はたらく
休む	やすむ
遊ぶ	あそぶ
泳ぐ	およぐ
飛ぶ	とぶ
歌う	うたう
踊る	おどる
笑う	わらう
泣く	なく
怒る	おこる
教える	おしえる
習う	ならう
分かる	わかる
知る	しる
考える	かんがえる
思う	おもう
言う	いう
答える	こたえる
始める	はじめる
終わる	おわる
開く	あく
閉める	しめる
押す	おす
引く	ひく
出かける	でかける
洗う	あらう
忘れる	わすれる
覚える	おぼえる
持つ	もつ
取る	とる
渡す	わたす
貸す	かす
借りる	かりる
返す	かえす
使う	つかう
切る	きる
私	わたし
僕	ぼく
俺	おれ
彼	かれ
彼女	かのじょ
人	ひと
男	おとこ
女	おんな
子供	こども
父	ちち
母	はは
兄	あに
姉	あね
弟	おとうと
妹	いもうと
家族	かぞく
友達	ともだち
先生	せんせい
学生	がくせい
名前	なまえ
本	ほん
雑誌	ざっし
新聞	しんぶん
辞書	じしょ
紙	かみ
鉛筆	えんぴつ
机	つくえ
椅子	いす
学校	がっこう
大学	だいがく
病院	びょういん
銀行	ぎんこう
郵便局	ゆうびんきょく
駅	えき
店	みせ
家	いえ
部屋	へや
台所	だいどころ
庭	にわ
道	みち
町	まち
国	くに
日本	にほん
電車	でんしゃ
車	くるま
自転車	じてんしゃ
飛行機	ひこうき
船	ふね
水	みず
お茶	おちゃ
牛乳	ぎゅうにゅう
ご飯	ごはん
肉	にく
魚	さかな
野菜	やさい
果物	くだもの
卵	たまご
朝ご飯	あさごはん
昼ご飯	ひるごはん
晩ご飯	ばんごはん
時間	じかん
今	いま
朝	あさ
昼	ひる
夜	よる
今日	きょう
明日	あした
昨日	きのう
今週	こんしゅう
来週	らいしゅう
先週	せんしゅう
今月	こんげつ
来月	らいげつ
先月	せんげつ
今年	ことし
来年	らいねん
去年	きょねん
毎日	まいにち
毎週	まいしゅう
毎月	まいつき
毎年	まいとし
週	しゅう
月	つき
年	とし
月曜日	げつようび
火曜日	かようび
水曜日	すいようび
木曜日	もくようび
金曜日	きんようび
土曜日	どようび
日曜日	にちようび
春	はる
夏	なつ
秋	あき
冬	ふゆ
天気	てんき
雨	あめ
雪	ゆき
風	かぜ
空	そら
山	やま
川	かわ
海	うみ
木	き
花	はな
犬	いぬ
猫	ねこ
鳥	とり
大きい	おおきい
小さい	ちいさい
高い	たかい
低い	ひくい
安い	やすい
新しい	あたらしい
古い	ふるい
良い	よい
悪い	わるい
多い	おおい
少ない	すくない
長い	ながい
短い	みじかい
広い	ひろい
狭い	せまい
重い	おもい
軽い	かるい
強い	つよい
弱い	よわい
速い	はやい
遅い	おそい
暑い	あつい
寒い	さむい
冷たい	つめたい
暖かい	あたたかい
涼しい	すずしい
明るい	あかるい
暗い	くらい
甘い	あまい
辛い	からい
おいしい	おいしい
楽しい	たのしい
面白い	おもしろい
うれしい	うれしい
悲しい	かなしい
忙しい	いそがしい
元気	げんき
有名	ゆうめい
便利	べんり
親切	しんせつ
静か	しずか
にぎやか	にぎやか
きれい	きれい
好き	すき
嫌い	きらい
上手	じょうず
下手	へた
同じ	おなじ
たくさん	たくさん
少し	すこし
ちょっと	ちょっと
全部	ぜんぶ
みんな	みんな
よく	よく
あまり	あまり
ぜんぜん	ぜんぜん
とても	とても
何	なに
誰	だれ
どこ	どこ
いつ	いつ
これ	これ
それ	それ
あれ	あれ
ここ	ここ
そこ	そこ
あそこ	あそこ
一	いち
二	に
三	さん
四	よん
五	ご
六	ろく
七	なな
八	はち
九	きゅう
十	じゅう
百	ひゃく
千	せん
万	まん
円	えん
半	はん
前	まえ
上	うえ
下	した
中	なか
外	そと
右	みぎ
左	ひだり
近く	ちかく
遠く	とおく
隣	となり
ありがとう	ありがとう
こんにちは	こんにちは
こんばんは	こんばんは
おはよう	おはよう
さようなら	さようなら
すみません	すみません
ごめんなさい	ごめんなさい
はい	はい
いいえ	いいえ
"""

    private const val N4_RAW = """
急ぐ	いそぐ
集める	あつめる
驚く	おどろく
祈る	いのる
動く	うごく
打つ	うつ
選ぶ	えらぶ
送る	おくる
落ちる	おちる
変える	かえる
飾る	かざる
勝つ	かつ
通う	かよう
変わる	かわる
決める	きめる
比べる	くらべる
込む	こむ
壊す	こわす
壊れる	こわれる
探す	さがす
下がる	さがる
誘う	さそう
触る	さわる
叱る	しかる
進む	すすむ
育てる	そだてる
倒れる	たおれる
助ける	たすける
建てる	たてる
着く	つく
続ける	つづける
釣る	つる
通る	とおる
届く	とどく
止まる	とまる
直す	なおす
慣れる	なれる
逃げる	にげる
盗む	ぬすむ
塗る	ぬる
似る	にる
残す	のこす
残る	のこる
運ぶ	はこぶ
払う	はらう
冷える	ひえる
光る	ひかる
踏む	ふむ
増える	ふえる
減る	へる
褒める	ほめる
曲がる	まがる
負ける	まける
回る	まわる
迎える	むかえる
戻る	もどる
焼く	やく
止む	やむ
辞める	やめる
揺れる	ゆれる
寄る	よる
喜ぶ	よろこぶ
沸く	わく
別れる	わかれる
渡る	わたる
珍しい	めずらしい
深い	ふかい
浅い	あさい
細い	ほそい
太い	ふとい
固い	かたい
柔らかい	やわらかい
苦い	にがい
眠い	ねむい
痛い	いたい
怖い	こわい
寂しい	さびしい
恥ずかしい	はずかしい
複雑	ふくざつ
簡単	かんたん
危険	きけん
安全	あんぜん
丁寧	ていねい
正直	しょうじき
真面目	まじめ
自由	じゆう
無理	むり
特別	とくべつ
適当	てきとう
十分	じゅうぶん
社会	しゃかい
歴史	れきし
文化	ぶんか
科学	かがく
技術	ぎじゅつ
産業	さんぎょう
工業	こうぎょう
農業	のうぎょう
社員	しゃいん
会議	かいぎ
予定	よてい
予約	よやく
意見	いけん
理由	りゆう
原因	げんいん
結果	けっか
場合	ばあい
機会	きかい
夢	ゆめ
気持ち	きもち
気分	きぶん
心	こころ
頭	あたま
顔	かお
目	め
鼻	はな
耳	みみ
口	くち
歯	は
首	くび
肩	かた
腕	うで
手	て
指	ゆび
足	あし
背中	せなか
お腹	おなか
体	からだ
血	ち
皮	かわ
髪	かみ
病気	びょうき
怪我	けが
熱	ねつ
咳	せき
薬	くすり
医者	いしゃ
入院	にゅういん
退院	たいいん
祖父	そふ
祖母	そぼ
両親	りょうしん
夫	おっと
妻	つま
息子	むすこ
娘	むすめ
赤ちゃん	あかちゃん
店員	てんいん
警察	けいさつ
泥棒	どろぼう
工場	こうじょう
事務所	じむしょ
受付	うけつけ
階段	かいだん
入り口	いりぐち
出口	でぐち
壁	かべ
窓	まど
屋根	やね
床	ゆか
家具	かぐ
布団	ふとん
枕	まくら
箒	ほうき
ゴミ	ゴミ
箱	はこ
袋	ふくろ
鍵	かぎ
傘	かさ
眼鏡	めがね
時計	とけい
財布	さいふ
鞄	かばん
帽子	ぼうし
靴	くつ
靴下	くつした
味	あじ
匂い	におい
音	おと
声	こえ
色	いろ
形	かたち
種類	しゅるい
量	りょう
数	かず
半分	はんぶん
予習	よしゅう
復習	ふくしゅう
宿題	しゅくだい
試験	しけん
作文	さくぶん
点	てん
昔	むかし
将来	しょうらい
最近	さいきん
最後	さいご
最初	さいしょ
途中	とちゅう
両方	りょうほう
全て	すべて
そろそろ	そろそろ
やっと	やっと
ずっと	ずっと
きっと	きっと
ぜひ	ぜひ
特に	とくに
急に	きゅうに
だんだん	だんだん
やはり	やはり
やっぱり	やっぱり
もうすぐ	もうすぐ
もちろん	もちろん
本当に	ほんとうに
たぶん	たぶん
つまり	つまり
それで	それで
そして	そして
それから	それから
だから	だから
しかし	しかし
でも	でも
けれど	けれど
"""

    // N3 — common intermediate vocabulary.
    private const val N3_RAW = """
効く	きく
描く	かく
試す	ためす
諦める	あきらめる
預ける	あずける
焦る	あせる
暴れる	あばれる
溢れる	あふれる
編む	あむ
表す	あらわす
案内する	あんないする
移す	うつす
浮かべる	うかべる
受け取る	うけとる
失う	うしなう
似合う	にあう
悩む	なやむ
並べる	ならべる
抜く	ぬく
濡らす	ぬらす
拝見する	はいけんする
計る	はかる
育つ	そだつ
確かめる	たしかめる
縮む	ちぢむ
伝える	つたえる
包む	つつむ
詰める	つめる
出会う	であう
解く	とく
戸惑う	とまどう
取り消す	とりけす
慰める	なぐさめる
怠ける	なまける
抜ける	ぬける
願う	ねがう
望む	のぞむ
図る	はかる
引っ越す	ひっこす
含む	ふくむ
防ぐ	ふせぐ
振る	ふる
巻く	まく
任せる	まかせる
招く	まねく
守る	まもる
見える	みえる
認める	みとめる
結ぶ	むすぶ
申し込む	もうしこむ
訳す	やくす
役立つ	やくだつ
焼ける	やける
雇う	やとう
許す	ゆるす
汚す	よごす
汚れる	よごれる
預かる	あずかる
当たる	あたる
安心する	あんしんする
案内	あんない
演奏	えんそう
横断	おうだん
応募	おうぼ
大型	おおがた
確認	かくにん
環境	かんきょう
関心	かんしん
完成	かんせい
観光	かんこう
機械	きかい
期待	きたい
規則	きそく
基本	きほん
急行	きゅうこう
苦労	くろう
訓練	くんれん
経験	けいけん
経済	けいざい
計算	けいさん
結婚	けっこん
健康	けんこう
検査	けんさ
工夫	くふう
故障	こしょう
国際	こくさい
困難	こんなん
事件	じけん
事故	じこ
自慢	じまん
失礼	しつれい
実験	じっけん
実際	じっさい
邪魔	じゃま
集中	しゅうちゅう
出席	しゅっせき
出張	しゅっちょう
趣味	しゅみ
順序	じゅんじょ
紹介	しょうかい
招待	しょうたい
商品	しょうひん
政治	せいじ
製品	せいひん
専門	せんもん
想像	そうぞう
相談	そうだん
卒業	そつぎょう
体験	たいけん
大事	だいじ
単純	たんじゅん
男性	だんせい
女性	じょせい
注意	ちゅうい
中止	ちゅうし
提案	ていあん
都会	とかい
努力	どりょく
内容	ないよう
苦手	にがて
人気	にんき
値段	ねだん
熱心	ねっしん
反対	はんたい
範囲	はんい
必要	ひつよう
表現	ひょうげん
不安	ふあん
普通	ふつう
平均	へいきん
平和	へいわ
変化	へんか
報告	ほうこく
方法	ほうほう
法律	ほうりつ
翻訳	ほんやく
満足	まんぞく
命令	めいれい
約束	やくそく
様子	ようす
利用	りよう
留学	りゅうがく
連絡	れんらく
記憶	きおく
記録	きろく
具体	ぐたい
原則	げんそく
姿勢	しせい
時代	じだい
自然	しぜん
実力	じつりょく
種類	しゅるい
状況	じょうきょう
身分	みぶん
責任	せきにん
存在	そんざい
態度	たいど
代表	だいひょう
知識	ちしき
直接	ちょくせつ
伝統	でんとう
独立	どくりつ
判断	はんだん
比較	ひかく
表面	ひょうめん
不思議	ふしぎ
変更	へんこう
方向	ほうこう
無料	むりょう
目的	もくてき
目標	もくひょう
予想	よそう
冷静	れいせい
"""

    // N2 — upper-intermediate.
    private const val N2_RAW = """
利く	きく
菊	きく
影響	えいきょう
演技	えんぎ
解釈	かいしゃく
改善	かいぜん
課題	かだい
観察	かんさつ
完璧	かんぺき
議論	ぎろん
強調	きょうちょう
競争	きょうそう
矛盾	むじゅん
機構	きこう
起源	きげん
救助	きゅうじょ
業績	ぎょうせき
検討	けんとう
効率	こうりつ
採用	さいよう
削除	さくじょ
指摘	してき
周辺	しゅうへん
主張	しゅちょう
状態	じょうたい
衝突	しょうとつ
推測	すいそく
制限	せいげん
接続	せつぞく
設備	せつび
速度	そくど
体系	たいけい
達成	たっせい
注目	ちゅうもく
抽象	ちゅうしょう
提供	ていきょう
程度	ていど
適切	てきせつ
統合	とうごう
動作	どうさ
同様	どうよう
投票	とうひょう
認識	にんしき
評価	ひょうか
分析	ぶんせき
平等	びょうどう
編集	へんしゅう
保存	ほぞん
名誉	めいよ
模型	もけい
役割	やくわり
油断	ゆだん
容易	ようい
要因	よういん
連携	れんけい
著しい	いちじるしい
怪しい	あやしい
鋭い	するどい
慌てる	あわてる
頂上	ちょうじょう
相応しい	ふさわしい
速やか	すみやか
異常	いじょう
一斉	いっせい
愛情	あいじょう
医療	いりょう
維持	いじ
活発	かっぱつ
活躍	かつやく
慎重	しんちょう
真剣	しんけん
資源	しげん
施設	しせつ
事情	じじょう
姿	すがた
湿気	しっけ
順番	じゅんばん
詳細	しょうさい
象徴	しょうちょう
処分	しょぶん
人類	じんるい
水準	すいじゅん
正常	せいじょう
精神	せいしん
製造	せいぞう
責任感	せきにんかん
組織	そしき
存続	そんぞく
対応	たいおう
体制	たいせい
逮捕	たいほ
妥当	だとう
担当	たんとう
著作	ちょさく
徹底	てってい
当然	とうぜん
独占	どくせん
入手	にゅうしゅ
背景	はいけい
発揮	はっき
判定	はんてい
否定	ひてい
表面化	ひょうめんか
復活	ふっかつ
分担	ぶんたん
平凡	へいぼん
"""

    // N1 — advanced. Conservative subset of widely-cited N1 entries.
    private const val N1_RAW = """
規矩	きく
詠む	よむ
拘束	こうそく
顧客	こきゃく
公務員	こうむいん
高揚	こうよう
高尚	こうしょう
困窮	こんきゅう
妥協	だきょう
統治	とうち
唯一	ゆいいつ
危惧	きぐ
憂慮	ゆうりょ
厳粛	げんしゅく
厳密	げんみつ
含蓄	がんちく
玄人	くろうと
派遣	はけん
賠償	ばいしょう
廃棄	はいき
反映	はんえい
否決	ひけつ
微塵	みじん
武装	ぶそう
復興	ふっこう
弊害	へいがい
偏差	へんさ
抽出	ちゅうしゅつ
緻密	ちみつ
直径	ちょっけい
賃金	ちんぎん
痛感	つうかん
提携	ていけい
哲学	てつがく
撤去	てっきょ
撤退	てったい
動揺	どうよう
督促	とくそく
突如	とつじょ
怒鳴る	どなる
愚か	おろか
忽然	こつぜん
頂	いただき
赴任	ふにん
赴く	おもむく
膨大	ぼうだい
膨張	ぼうちょう
模倣	もほう
妥結	だけつ
脅威	きょうい
脅迫	きょうはく
凝視	ぎょうし
魂	たましい
享受	きょうじゅ
携わる	たずさわる
携帯	けいたい
携帯品	けいたいひん
顕著	けんちょ
献身	けんしん
献立	こんだて
妥当性	だとうせい
怠惰	たいだ
怠慢	たいまん
拒否	きょひ
排除	はいじょ
排他	はいた
配慮	はいりょ
派手	はで
培養	ばいよう
"""
}

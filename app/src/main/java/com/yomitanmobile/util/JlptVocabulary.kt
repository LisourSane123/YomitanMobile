package com.yomitanmobile.util

/**
 * Built-in JLPT level fallback for dictionaries that don't ship JLPT tags
 * (most JMDict variants except Jitendex). Lookup keys are (expression, reading)
 * pairs. Lookups also fall back to expression-only or reading-only matches so
 * kana-only words and kanji written in kana still resolve.
 *
 * Used as a fallback when [com.yomitanmobile.data.local.entity.DictionaryEntry.jlptLevel]
 * is 0 — the dictionary's own JLPT data wins when present.
 *
 * Data is a curated subset of essential N5/N4 vocabulary. Words not in this
 * list simply return 0 (no level).
 */
object JlptVocabulary {

    /**
     * Returns 1-5 (N1-N5) if the word is in the built-in vocabulary, else 0.
     */
    fun getLevel(expression: String, reading: String): Int {
        val exp = expression.trim()
        val read = reading.trim()
        if (exp.isEmpty() && read.isEmpty()) return 0

        // Prefer exact (expression, reading) match
        if (exp.isNotEmpty() && read.isNotEmpty()) {
            n5Pairs[exp to read]?.let { return it }
            n4Pairs[exp to read]?.let { return it }
        }
        // Fall back to expression-only match
        if (exp.isNotEmpty()) {
            n5Expressions[exp]?.let { return it }
            n4Expressions[exp]?.let { return it }
        }
        // Fall back to reading-only match (covers kana-only entries)
        if (read.isNotEmpty()) {
            n5Expressions[read]?.let { return it }
            n4Expressions[read]?.let { return it }
        }
        return 0
    }

    // Maps for fast lookup. Keys are derived from RAW data once at class load.
    private val n5Pairs: Map<Pair<String, String>, Int> by lazy { buildPairs(N5_RAW, 5) }
    private val n4Pairs: Map<Pair<String, String>, Int> by lazy { buildPairs(N4_RAW, 4) }
    private val n5Expressions: Map<String, Int> by lazy { buildExpressions(N5_RAW, 5) }
    private val n4Expressions: Map<String, Int> by lazy { buildExpressions(N4_RAW, 4) }

    private fun buildPairs(raw: String, level: Int): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split('\t', limit = 2)
            if (parts.size == 2) {
                out[parts[0] to parts[1]] = level
            } else {
                out[parts[0] to parts[0]] = level
            }
        }
        return out
    }

    private fun buildExpressions(raw: String, level: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split('\t', limit = 2)
            // Index expression and reading independently so either lookup hits.
            out.putIfAbsent(parts[0], level)
            if (parts.size == 2) out.putIfAbsent(parts[1], level)
        }
        return out
    }

    // Format: expression\treading per line. Kana-only entries: just the reading.
    // Sources: standard JLPT N5 vocabulary lists (Tanos, common textbooks).
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
勉強する	べんきょうする
分かる	わかる
知る	しる
考える	かんがえる
思う	おもう
言う	いう
答える	こたえる
質問する	しつもんする
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
作る	つくる
切る	きる
ある	ある
いる	いる
できる	できる
ほしい	ほしい
私	わたし
僕	ぼく
俺	おれ
あなた	あなた
彼	かれ
彼女	かのじょ
人	ひと
男	おとこ
女	おんな
子供	こども
子	こ
父	ちち
母	はは
お父さん	おとうさん
お母さん	おかあさん
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
ペン	ペン
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
お風呂	おふろ
トイレ	トイレ
庭	にわ
道	みち
町	まち
国	くに
日本	にほん
電車	でんしゃ
バス	バス
車	くるま
自転車	じてんしゃ
飛行機	ひこうき
船	ふね
水	みず
お茶	おちゃ
コーヒー	コーヒー
お酒	おさけ
牛乳	ぎゅうにゅう
パン	パン
ご飯	ごはん
肉	にく
魚	さかな
野菜	やさい
果物	くだもの
りんご	りんご
バナナ	バナナ
卵	たまご
お弁当	おべんとう
朝ご飯	あさごはん
昼ご飯	ひるごはん
晩ご飯	ばんごはん
時	とき
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
日	ひ
年	ねん
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
魚	さかな
大きい	おおきい
小さい	ちいさい
高い	たかい
低い	ひくい
安い	やすい
新しい	あたらしい
古い	ふるい
良い	よい
いい	いい
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
熱い	あつい
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
まずい	まずい
楽しい	たのしい
面白い	おもしろい
つまらない	つまらない
うれしい	うれしい
悲しい	かなしい
忙しい	いそがしい
元気	げんき
有名	ゆうめい
便利	べんり
不便	ふべん
親切	しんせつ
静か	しずか
にぎやか	にぎやか
きれい	きれい
ハンサム	ハンサム
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
本当	ほんとう
何	なに
何	なん
誰	だれ
どこ	どこ
いつ	いつ
どう	どう
どの	どの
どれ	どれ
これ	これ
それ	それ
あれ	あれ
この	この
その	その
あの	あの
ここ	ここ
そこ	そこ
あそこ	あそこ
こちら	こちら
そちら	そちら
あちら	あちら
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
時	じ
分	ふん
半	はん
後	ご
前	まえ
上	うえ
下	した
中	なか
外	そと
右	みぎ
左	ひだり
横	よこ
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
そうです	そうです
じゃあ	じゃあ
"""

    private const val N4_RAW = """
楽しむ	たのしむ
集める	あつめる
驚く	おどろく
急ぐ	いそぐ
祈る	いのる
動く	うごく
打つ	うつ
選ぶ	えらぶ
送る	おくる
落ちる	おちる
踊る	おどる
覚える	おぼえる
思い出す	おもいだす
変える	かえる
飾る	かざる
勝つ	かつ
通う	かよう
枯れる	かれる
変わる	かわる
気がつく	きがつく
聞こえる	きこえる
決める	きめる
比べる	くらべる
暮れる	くれる
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
試す	ためす
着く	つく
続ける	つづける
釣る	つる
出来る	できる
通る	とおる
届く	とどく
飛ぶ	とぶ
止まる	とまる
直す	なおす
鳴く	なく
慣れる	なれる
逃げる	にげる
盗む	ぬすむ
塗る	ぬる
似る	にる
寝坊する	ねぼうする
残す	のこす
残る	のこる
乗り換える	のりかえる
運ぶ	はこぶ
払う	はらう
冷える	ひえる
光る	ひかる
ぶつかる	ぶつかる
踏む	ふむ
増える	ふえる
減る	へる
褒める	ほめる
曲がる	まがる
負ける	まける
間違える	まちがえる
回る	まわる
迎える	むかえる
召し上がる	めしあがる
申す	もうす
戻す	もどす
戻る	もどる
焼く	やく
やる	やる
止む	やむ
辞める	やめる
揺れる	ゆれる
寄る	よる
喜ぶ	よろこぶ
沸く	わく
別れる	わかれる
渡る	わたる
珍しい	めずらしい
すごい	すごい
深い	ふかい
浅い	あさい
細い	ほそい
太い	ふとい
固い	かたい
柔らかい	やわらかい
苦い	にがい
酸っぱい	すっぱい
眠い	ねむい
痛い	いたい
かゆい	かゆい
怖い	こわい
寂しい	さびしい
恥ずかしい	はずかしい
立派	りっぱ
複雑	ふくざつ
簡単	かんたん
危険	きけん
安全	あんぜん
丁寧	ていねい
失礼	しつれい
正直	しょうじき
真面目	まじめ
自由	じゆう
無理	むり
特別	とくべつ
適当	てきとう
十分	じゅうぶん
熱心	ねっしん
盛ん	さかん
柔らか	やわらか
法律	ほうりつ
社会	しゃかい
政治	せいじ
経済	けいざい
歴史	れきし
文化	ぶんか
宗教	しゅうきょう
科学	かがく
技術	ぎじゅつ
産業	さんぎょう
工業	こうぎょう
農業	のうぎょう
社員	しゃいん
会議	かいぎ
契約	けいやく
予定	よてい
予約	よやく
意見	いけん
理由	りゆう
原因	げんいん
結果	けっか
方法	ほうほう
場合	ばあい
機会	きかい
経験	けいけん
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
舌	した
首	くび
肩	かた
腕	うで
手	て
指	ゆび
足	あし
背中	せなか
腰	こし
お腹	おなか
体	からだ
血	ち
骨	ほね
皮	かわ
髪	かみ
ひげ	ひげ
病気	びょうき
怪我	けが
熱	ねつ
咳	せき
薬	くすり
医者	いしゃ
看護師	かんごし
歯医者	はいしゃ
入院	にゅういん
退院	たいいん
祖父	そふ
祖母	そぼ
おじいさん	おじいさん
おばあさん	おばあさん
おじさん	おじさん
おばさん	おばさん
両親	りょうしん
夫	おっと
妻	つま
息子	むすこ
娘	むすめ
赤ちゃん	あかちゃん
お客さん	おきゃくさん
店員	てんいん
運転手	うんてんしゅ
警察	けいさつ
泥棒	どろぼう
工場	こうじょう
事務所	じむしょ
受付	うけつけ
階段	かいだん
入り口	いりぐち
出口	でぐち
門	もん
壁	かべ
窓	まど
屋根	やね
床	ゆか
天井	てんじょう
家具	かぐ
押し入れ	おしいれ
引き出し	ひきだし
布団	ふとん
枕	まくら
毛布	もうふ
箒	ほうき
雑巾	ぞうきん
ゴミ	ゴミ
紙	かみ
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
シャツ	シャツ
ズボン	ズボン
洋服	ようふく
着物	きもの
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
全部	ぜんぶ
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
僕	ぼく
私	わたくし
彼ら	かれら
皆	みな
皆さん	みなさん
自分	じぶん
他	ほか
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
なるべく	なるべく
ちっとも	ちっとも
やはり	やはり
やっぱり	やっぱり
もうすぐ	もうすぐ
もちろん	もちろん
本当に	ほんとうに
たぶん	たぶん
きゅうに	きゅうに
つまり	つまり
それで	それで
そして	そして
それから	それから
だから	だから
しかし	しかし
でも	でも
けれど	けれど
けれども	けれども
ところで	ところで
"""
}

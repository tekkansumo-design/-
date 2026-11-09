package com.tekkansumo.ebayscanner

/**
 * 値札の文字認識結果から「仕入れ値（円）」を推定する。
 *
 * 値札にはたいてい複数の数字がある（定価・税抜・税込・JAN など）。
 * いちばん「それらしい」ものを点数で選ぶ:
 *  - 文字が大きいほど高得点（売価はたいてい一番大きく書かれる）
 *  - 「税込」「売価」「特価」などが同じ行にあれば加点
 *  - 「定価」「参考」「通常」など、実際に払う値段ではない語があれば減点
 */
object PriceParser {

    /** 認識した 1 行。height は文字の高さ（px）。 */
    data class Line(val text: String, val height: Int)

    data class Candidate(val yen: Int, val score: Double, val line: String)

    private const val NUM = """(\d{1,3}(?:[,.]\d{3})+|\d{2,7})"""
    private val YEN_PREFIX = Regex("""[¥\\]\s*$NUM(?!\d)""")
    private val YEN_SUFFIX = Regex("""(?<![\d,.])$NUM\s*円""")
    private val TAX_LABEL = Regex("""(税込|売価|販売価格|特価|お買い?得|SALE|セール|価格)""", RegexOption.IGNORE_CASE)
    private val TAX_LABEL_NUM = Regex("""(?:税込|売価|販売価格|特価)\s*[:：]?\s*$NUM""")
    private val NOT_PAY = Regex("""(定価|参考|通常|メーカー|希望小売|元値|旧価格|ポイント|pt|OFF|引き|値引)""", RegexOption.IGNORE_CASE)
    private val EX_TAX = Regex("""(税抜|本体|税別)""")

    const val MIN_YEN = 10
    const val MAX_YEN = 3_000_000

    /** 全角数字・記号を半角に寄せる。 */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '￥' -> '¥'
                    '，', '、' -> ','
                    '．' -> '.'
                    '：' -> ':'
                    '　' -> ' '
                    else -> c
                }
            )
        }
        // 「1, 980」のように桁区切りの後ろに入った空白を詰める
        return sb.toString().replace(Regex("""(\d)[,.]\s+(\d{3})"""), "$1,$2")
    }

    private fun toYen(raw: String): Int? {
        val digits = raw.replace(",", "").replace(".", "")
        val v = digits.toLongOrNull() ?: return null
        if (v < MIN_YEN || v > MAX_YEN) return null
        return v.toInt()
    }

    fun candidates(lines: List<Line>): List<Candidate> {
        if (lines.isEmpty()) return emptyList()
        val maxH = lines.maxOf { it.height }.coerceAtLeast(1).toDouble()
        val out = ArrayList<Candidate>()
        for (l in lines) {
            val t = normalize(l.text)
            val found = LinkedHashSet<Int>()
            for (re in listOf(YEN_PREFIX, YEN_SUFFIX, TAX_LABEL_NUM)) {
                re.findAll(t).forEach { m -> toYen(m.groupValues[1])?.let(found::add) }
            }
            if (found.isEmpty()) continue
            var score = 0.3 + 0.7 * (l.height / maxH)
            // 税抜の行は実際に払う額ではないので加点しない
            if (EX_TAX.containsMatchIn(t)) score -= 0.3
            else if (TAX_LABEL.containsMatchIn(t)) score += 0.35
            if (NOT_PAY.containsMatchIn(t)) score -= 0.5
            for (y in found) out += Candidate(y, score, l.text)
        }
        return out.sortedByDescending { it.score }
    }

    /** 最も売価らしい金額。見つからなければ null。 */
    fun parse(lines: List<Line>): Int? = candidates(lines).firstOrNull()?.yen
}

/**
 * フレームごとの値札読み取りはブレるので、
 * 直近のフレームで 2 回以上同じ金額が出たら「確定」とする。
 * 確定した金額は、別の金額が確定するか一定時間経つまで保持する
 * （値札を読んだあとカメラを商品に向けても値段を覚えておくため）。
 */
class PriceStabilizer(
    private val window: Int = 4,
    private val needed: Int = 2,
    private val holdMillis: Long = 30_000,
) {
    private val recent = ArrayDeque<Int?>()
    var confirmed: Int? = null
        private set
    var manual = false
        private set
    private var confirmedAt = 0L

    /** 新しい読み取りを入れる。確定値が変わったら true。 */
    fun offer(yen: Int?, now: Long): Boolean {
        recent.addLast(yen)
        while (recent.size > window) recent.removeFirst()
        if (manual) return false
        val before = confirmed
        if (yen != null && recent.count { it == yen } >= needed) {
            confirmed = yen
            confirmedAt = now
        } else if (confirmed != null && now - confirmedAt > holdMillis) {
            confirmed = null
        }
        return before != confirmed
    }

    /** 手入力した値段は、リセットするまで固定する。 */
    fun setManual(yen: Int?) {
        manual = yen != null
        confirmed = yen
        recent.clear()
    }

    fun reset() = setManual(null)
}

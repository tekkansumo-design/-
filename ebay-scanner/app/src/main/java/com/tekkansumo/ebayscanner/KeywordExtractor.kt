package com.tekkansumo.ebayscanner

/**
 * パッケージや値札の文字から、eBay で検索できる英数字のキーワードを作る。
 * eBay は英語のサイトなので、日本語の商品名よりも
 * 「型番」と「ブランド名」の方がずっと正確に当たる。
 */
object KeywordExtractor {

    val BRANDS = setOf(
        "SONY", "NINTENDO", "BANDAI", "NAMCO", "SEGA", "CAPCOM", "KONAMI", "SQUARE", "ENIX",
        "TAKARA", "TOMY", "TOMICA", "KOTOBUKIYA", "GOODSMILE", "MEDICOM", "MEGAHOUSE", "FIGMA",
        "NENDOROID", "GUNDAM", "GUNPLA", "POKEMON", "SANRIO", "STUDIO", "GHIBLI", "TAMIYA", "HASEGAWA",
        "CANON", "NIKON", "OLYMPUS", "PENTAX", "FUJIFILM", "RICOH", "MINOLTA", "KONICA", "SIGMA",
        "TAMRON", "LEICA", "PANASONIC", "LUMIX", "TOSHIBA", "SHARP", "HITACHI", "CASIO", "SEIKO",
        "CITIZEN", "ORIENT", "GRAND", "YAMAHA", "ROLAND", "KORG", "BOSS", "TEAC", "TASCAM", "DENON",
        "ONKYO", "PIONEER", "KENWOOD", "AUDIO", "TECHNICA", "TECHNICS", "AIWA", "JVC", "VICTOR",
        "SHIMANO", "DAIWA", "MEGABASS", "ZOJIRUSHI", "TIGER", "MUJI", "UNIQLO", "BEAMS", "PORTER",
        "ASICS", "MIZUNO", "ONITSUKA", "SNOWPEAK", "MONTBELL", "PILOT", "SAILOR", "PLATINUM",
        "ZEBRA", "UNI", "PENTEL", "MAKITA", "HIKOKI", "GLOBAL", "MIYABI", "TOTO", "HELLO", "KITTY",
        "SUPREME", "APPLE", "IPHONE", "IPAD", "BOSE", "MICROSOFT", "XBOX", "PLAYSTATION", "SWITCH",
        "GAMEBOY", "FAMICOM", "SUPER", "DISNEY", "MARVEL", "LEGO", "HOTWHEELS", "BANPRESTO",
        "SEGA", "FURYU", "TAITO", "ICHIBAN", "KUJI", "ONEPIECE", "NARUTO", "DRAGONBALL",
    )

    private val STOP = setOf(
        "MADE", "JAPAN", "CHINA", "TAIWAN", "VIETNAM", "THAILAND", "WITH", "THE", "AND", "FOR",
        "PRICE", "TAX", "YEN", "JAN", "SALE", "NEW", "USED", "ITEM", "CODE", "SIZE", "COLOR",
        "TYPE", "MODEL", "LIMITED", "EDITION", "SERIES", "VERSION", "OFFICIAL", "LICENSED",
        "WARNING", "CAUTION", "AGES", "YEARS", "OLDER", "SMALL", "PARTS", "INCLUDED", "BATTERY",
        "BATTERIES", "INC", "LTD", "CORP", "CO", "COPYRIGHT", "ALL", "RIGHTS", "RESERVED",
        "WWW", "COM", "HTTP", "HTTPS", "POINT", "OFF", "BOOKOFF", "HARDOFF", "HOBBYOFF", "OFFHOUSE",
        "SURUGAYA", "STORE", "SHOP", "MARKET", "BARCODE", "ISBN", "BOX", "SET", "PACK",
    )

    private val UNIT = Regex("""^\d+(ML|L|G|KG|MG|MM|CM|M|W|V|MAH|GB|TB|MB|HZ|KHZ|PCS|P|PC|CC|OZ|IN|X\d+)$""")
    private val YEARISH = Regex("""^(19|20)\d{2}$""")
    private val TOKEN = Regex("""[A-Z0-9][A-Z0-9\-]*[A-Z0-9]|[A-Z0-9]""")

    data class Query(val text: String, val model: String?)

    private fun isModel(t: String): Boolean {
        val bare = t.replace("-", "")
        if (bare.length < 4 || bare.length > 20) return false
        if (!bare.any { it.isDigit() } || !bare.any { it.isLetter() }) return false
        if (UNIT.matches(bare)) return false
        if (bare.startsWith("JAN") || bare.startsWith("ISBN")) return false
        // 数字ばかりで英字が 1 文字だけ、かつ長い → バーコードの読み違いのことが多い
        if (bare.count { it.isDigit() } >= 9) return false
        return true
    }

    /** 検索に使えるキーワードが無ければ null。 */
    fun extract(text: String): Query? {
        val up = PriceParser.normalize(text).uppercase()
            .replace('‐', '-').replace('−', '-').replace('ー', '-').replace('–', '-')
        val tokens = TOKEN.findAll(up).map { it.value }.toList()

        val brands = LinkedHashSet<String>()
        val models = LinkedHashSet<String>()
        val words = LinkedHashSet<String>()
        for (t in tokens) {
            when {
                t in BRANDS -> brands += t
                isModel(t) -> models += t
                t.length >= 4 && t.all { it.isLetter() } && t !in STOP && !YEARISH.matches(t) -> words += t
            }
        }
        val bestModels = models.sortedByDescending { it.replace("-", "").length }.take(2)
        val parts = ArrayList<String>()
        parts += brands.take(2)
        if (bestModels.isNotEmpty()) {
            parts += bestModels
        } else {
            if (brands.isEmpty() || words.isEmpty()) return null
            parts += words.take(2)
        }
        return Query(parts.joinToString(" "), bestModels.firstOrNull())
    }

    /** 型番が eBay のタイトルに含まれているか（ハイフン・空白の有無は無視）。 */
    fun titleHasModel(title: String, model: String): Boolean {
        val norm = { s: String -> s.uppercase().replace(Regex("""[\s\-‐/_.]"""), "") }
        return norm(title).contains(norm(model))
    }
}

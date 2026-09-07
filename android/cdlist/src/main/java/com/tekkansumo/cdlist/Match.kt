package com.tekkansumo.cdlist

import java.text.Normalizer

/**
 * 作品名と商品名の突き合わせ。
 *
 * 全角半角・大小文字・記号・空白の違いを落としてから比べる。
 * 「First Love」と「ＦＩＲＳＴ　ＬＯＶＥ」を別物にしないため。
 */
object Match {

    private val DROP = Regex(
        "[\\s\\p{Punct}　・…‐‑‒–—―〜～「」『』【】〔〕（）［］｛｝、。，．〈〉《》]"
    )

    fun key(s: String): String =
        DROP.replace(Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(), "")

    /**
     * 一致度。作品名が一致しないものは 0（＝拾わない）。
     * アーティスト名まで一致したら +2 して、同名異物と区別できるようにする。
     */
    fun score(itemTitle: String, artistKey: String, titleKey: String): Int {
        val ik = key(itemTitle)
        if (ik.isEmpty() || titleKey.isEmpty()) return 0
        var s = when {
            ik.contains(titleKey) -> 3
            // 「(初回限定盤)」などで末尾が削れている場合を拾う
            titleKey.length >= 6 &&
                ik.contains(titleKey.substring(0, titleKey.length * 2 / 3)) -> 2
            else -> 0
        }
        if (s > 0 && artistKey.isNotEmpty() && ik.contains(artistKey)) s += 2
        return s
    }
}

package com.tekkansumo.cdlist

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持っている CD の控え。チェックを入れた時点で書き出す（保存ボタンは無い）。
 *
 * 鍵は MusicBrainz の release-group ID。題名が変わっても紐づけが切れない。
 */
object Store {

    fun load(f: File): JSONObject =
        try {
            if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (e: Exception) {
            JSONObject()        // 壊れていても起動は止めない
        }

    @Synchronized
    fun setOwned(f: File, key: String, owned: Boolean, meta: JSONObject?): Int {
        val d = load(f)
        if (owned) {
            val rec = meta ?: JSONObject()
            rec.put("ts", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date()))
            d.put(key, rec)
        } else {
            d.remove(key)       // 外したものは残さない
        }
        write(f, d)
        return d.length()
    }

    /** 書き換え中に落ちても元が消えないよう、一時ファイル経由で差し替える。 */
    private fun write(f: File, d: JSONObject) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(d.toString(1), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(d.toString(1), Charsets.UTF_8)
            tmp.delete()
        }
    }
}

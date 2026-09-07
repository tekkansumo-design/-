package com.tekkansumo.cdlist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MusicBrainz の応答の読み取り。通信と切り離してあるので単体で確かめられる。 */
class MusicBrainzTest {

    @Test
    fun `日本語の別名を拾って候補に添える`() {
        val json = """
            {"artists":[{"id":"b2c8a05e-0000-4000-8000-000000000001",
             "name":"Hikaru Utada","score":100,"country":"JP","type":"Person",
             "disambiguation":"","aliases":[{"name":"Utada Hikaru"},{"name":"宇多田ヒカル"}]}]}
        """.trimIndent()
        val a = MusicBrainz.parseArtists(json).getJSONObject(0)
        assertEquals("Hikaru Utada", a.getString("name"))
        assertEquals("宇多田ヒカル", a.getString("alias"))
        assertEquals("JP", a.getString("area"))
    }

    @Test
    fun `アルバムとシングルを種別で分ける`() {
        val json = """
            {"release-group-count":3,"release-groups":[
             {"id":"rg-a","title":"First Love","first-release-date":"1999-03-10",
              "primary-type":"Album","secondary-types":[]},
             {"id":"rg-b","title":"Automatic","first-release-date":"1998-12-09",
              "primary-type":"Single","secondary-types":[]},
             {"id":"rg-c","title":"SINGLE COLLECTION","first-release-date":"2004-03-31",
              "primary-type":"Album","secondary-types":["Compilation"]}]}
        """.trimIndent()
        val (arr, total) = MusicBrainz.parsePage(json)
        assertEquals(3, total)
        assertEquals("album", arr.getJSONObject(0).getString("kind"))
        assertEquals("single", arr.getJSONObject(1).getString("kind"))
        assertEquals("編集盤", arr.getJSONObject(2).getString("sub"))
    }

    @Test
    fun `新しい順に並べ、日付の無いものは末尾へ`() {
        val (arr, _) = MusicBrainz.parsePage(
            """
            {"release-groups":[
             {"id":"1","title":"古い","first-release-date":"1998-12-09","primary-type":"Single"},
             {"id":"2","title":"日付なし","primary-type":"Album"},
             {"id":"3","title":"新しい","first-release-date":"2004-03-31","primary-type":"Album"}]}
            """.trimIndent()
        )
        val sorted = MusicBrainz.sortNewestFirst(arr)
        assertEquals("新しい", sorted.getJSONObject(0).getString("title"))
        assertEquals("古い", sorted.getJSONObject(1).getString("title"))
        assertEquals("日付なし", sorted.getJSONObject(2).getString("title"))
    }

    @Test
    fun `持っている印は保存済みの一覧から復元する`() {
        val (arr, _) = MusicBrainz.parsePage(
            """{"release-groups":[{"id":"rg-a","title":"First Love","primary-type":"Album"}]}"""
        )
        val owned = JSONObject().put("rg-a", JSONObject().put("title", "First Love"))
        val o = arr.getJSONObject(0)
        o.put("owned", owned.has(o.optString("id")))
        assertTrue(o.getBoolean("owned"))
    }
}

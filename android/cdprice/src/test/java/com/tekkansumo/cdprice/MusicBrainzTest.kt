package com.tekkansumo.cdprice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicBrainz の応答の読み取り。
 * 実際の API には CI から当てられないので、公開されている応答の形で確かめる。
 * https://musicbrainz.org/doc/MusicBrainz_API
 */
class MusicBrainzTest {

    private val artistJson = """
    {"created":"2026-01-01T00:00:00.000Z","count":2,"offset":0,"artists":[
      {"id":"c7a3d0ca-0cf7-40c7-b4d1-b3b0b3b0b3b0","type":"Person","score":100,
       "name":"Hikaru Utada","sort-name":"Utada, Hikaru","country":"JP",
       "disambiguation":"Japanese-American singer",
       "life-span":{"begin":"1983-01-19","ended":false},
       "aliases":[{"name":"宇多田ヒカル","locale":"ja","primary":true},
                  {"name":"Utada","locale":null,"primary":null}]},
      {"id":"11111111-2222-3333-4444-555555555555","type":"Group","score":52,
       "name":"Utada Hikaru Tribute Band","sort-name":"Utada Hikaru Tribute Band",
       "life-span":{}}
    ]}
    """

    private val albumJson = """
    {"release-group-count":3,"release-group-offset":0,"release-groups":[
      {"id":"a1","title":"DEEP RIVER","first-release-date":"2002-06-19",
       "primary-type":"Album","secondary-types":[]},
      {"id":"a2","title":"First Love","first-release-date":"1999-03-10",
       "primary-type":"Album","secondary-types":[]},
      {"id":"a3","title":"SINGLE COLLECTION VOL.1","first-release-date":"2004-03-31",
       "primary-type":"Album","secondary-types":["Compilation"]}
    ]}
    """

    @Test
    fun `アーティスト候補を読み取れる`() {
        val list = MusicBrainz.parseArtists(artistJson)
        assertEquals(2, list.size)
        assertEquals("Hikaru Utada", list[0].name)
        assertEquals("JP", list[0].country)
        assertEquals("1983-01-19", list[0].begin)
    }

    @Test
    fun `日本語の別名を拾える`() {
        val list = MusicBrainz.parseArtists(artistJson)
        assertEquals("宇多田ヒカル", list[0].japanese)
        assertNull(list[1].japanese)   // 別名が無いほう
    }

    @Test
    fun `候補の一行に国と年が入る`() {
        val line = MusicBrainz.parseArtists(artistJson)[0].line
        assertTrue(line.startsWith("Hikaru Utada"))
        assertTrue(line.contains("JP"))
        assertTrue(line.contains("1983"))
    }

    @Test
    fun `アルバムを発売の古い順に並べる`() {
        val (page, total) = MusicBrainz.parseAlbums(albumJson)
        assertEquals(3, total)
        val list = MusicBrainz.tidy(page)
        assertEquals(listOf("First Love", "DEEP RIVER", "SINGLE COLLECTION VOL.1"), list.map { it.title })
        assertEquals("1999", list[0].year)
    }

    @Test
    fun `ベスト盤に印がつく`() {
        val list = MusicBrainz.tidy(MusicBrainz.parseAlbums(albumJson).first)
        assertEquals("", list.first { it.title == "First Love" }.kind)
        assertEquals("ベスト", list.first { it.title.startsWith("SINGLE") }.kind)
    }

    @Test
    fun `同じ題名の重複は古いほうを残す`() {
        val dup = """
        {"release-group-count":2,"release-groups":[
          {"id":"x1","title":"First Love","first-release-date":"2014-03-10",
           "primary-type":"Album","secondary-types":[]},
          {"id":"x2","title":"first love","first-release-date":"1999-03-10",
           "primary-type":"Album","secondary-types":[]}
        ]}
        """
        val list = MusicBrainz.tidy(MusicBrainz.parseAlbums(dup).first)
        assertEquals(1, list.size)
        assertEquals("1999", list[0].year)
    }

    @Test
    fun `中身が無い応答でも落ちない`() {
        assertEquals(emptyList<ArtistRef>(), MusicBrainz.parseArtists("""{"count":0}"""))
        assertEquals(0, MusicBrainz.parseAlbums("""{}""").first.size)
    }
}

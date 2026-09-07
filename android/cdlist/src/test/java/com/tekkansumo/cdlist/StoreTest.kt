package com.tekkansumo.cdlist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 所有の控え。チェックした時点で書き出し、外したら消えることを確かめる。 */
class StoreTest {

    private fun tmp(): File =
        File.createTempFile("owned", ".json").apply { delete() }

    @Test
    fun `チェックすると保存され、外すと消える`() {
        val f = tmp()
        val meta = JSONObject().put("title", "First Love").put("artist", "宇多田ヒカル")
        assertEquals(1, Store.setOwned(f, "rg-a", true, meta))
        assertTrue(Store.load(f).has("rg-a"))
        assertEquals("First Love", Store.load(f).getJSONObject("rg-a").getString("title"))

        assertEquals(0, Store.setOwned(f, "rg-a", false, null))
        assertFalse(Store.load(f).has("rg-a"))
        f.delete()
    }

    @Test
    fun `壊れたファイルでも空として読み、起動を止めない`() {
        val f = tmp()
        f.writeText("{壊れている")
        assertEquals(0, Store.load(f).length())
        f.delete()
    }
}

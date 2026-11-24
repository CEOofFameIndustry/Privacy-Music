package com.example.privacymusic

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every UI string must exist in every language file, with the same {n} placeholders. */
class I18nTest {
    private val dir = File("src/main/assets/i18n")
    private fun load(name: String) = JSONObject(File(dir, "$name.json").readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    private val ph = Regex("\\{\\d}")

    @Test fun everyLanguageIsComplete() {
        val source = load("source")
        val langs = dir.listFiles()!!.map { it.nameWithoutExtension }.filter { it != "source" }
        assertTrue(langs.size >= 10)
        for (lang in langs) {
            val t = load(lang)
            val missing = source.keys.filter { it !in t }
            assertTrue("$lang is missing $missing", missing.isEmpty())
            val bad = source.filter { (k, v) -> ph.findAll(v).map { it.value }.toSet() != ph.findAll(t.getValue(k)).map { it.value }.toSet() }.keys
            assertTrue("$lang has wrong placeholders in $bad", bad.isEmpty())
        }
    }
}

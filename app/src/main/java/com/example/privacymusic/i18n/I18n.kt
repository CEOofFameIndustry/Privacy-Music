package com.example.privacymusic.i18n

import android.content.Context
import com.example.privacymusic.data.Settings
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface translations. Ukrainian is the source language written in the code; every other language is a
 * JSON file in assets/i18n (id -> text), with source.json mapping ids to the Ukrainian originals.
 * Reading the language goes through a snapshot-backed setting, so switching language re-composes the UI
 * instantly — no restart, playback keeps going.
 */
object I18n {
    /** Native names; "system" follows the phone's language (English if we don't have it). */
    val languages = listOf(
        "uk" to "Українська", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
        "pt" to "Português", "it" to "Italiano", "pl" to "Polski", "tr" to "Türkçe", "zh" to "简体中文", "ja" to "日本語",
    )
    private val supported = languages.map { it.first }.toSet()
    private lateinit var app: Context
    private val tables = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var ids: Map<String, String> = emptyMap()

    fun init(context: Context) {
        app = context.applicationContext
        ids = load("source").entries.associate { (id, uk) -> uk to id }
    }

    private fun load(name: String): Map<String, String> = runCatching {
        val o = JSONObject(app.assets.open("i18n/$name.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun systemLanguage(): String = Locale.getDefault().language.let { if (it in supported) it else "en" }

    /** The language the UI is shown in right now. */
    fun current(): String = Settings.uiLanguage.value.let { if (it == "system" || it !in supported) systemLanguage() else it }

    fun locale(): Locale = Locale.forLanguageTag(current())

    fun lookup(lang: String, source: String): String? {
        if (!::app.isInitialized) return null
        val id = ids[source] ?: return null
        return tables.getOrPut(lang) { load(lang) }[id]
    }
}

/** Translate a Ukrainian UI string; `{0}`, `{1}`… are replaced by [args]. */
fun T(source: String, vararg args: Any?): String {
    val lang = I18n.current()
    var text = if (lang == "uk") source else I18n.lookup(lang, source) ?: I18n.lookup("en", source) ?: source
    args.forEachIndexed { i, a -> text = text.replace("{$i}", a.toString()) }
    return text
}

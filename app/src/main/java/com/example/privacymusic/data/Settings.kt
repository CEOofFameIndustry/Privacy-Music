package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class AudioQuality(private val raw: String) { AUTO("Авто (висока на Wi‑Fi)"), HIGH("Висока"), LOW("Економна") ; val label: String get() = T(raw) }

/** All settings live in a private SharedPreferences file on the device; nothing is synced anywhere. */
object Settings {
    private lateinit var prefs: SharedPreferences

    class Pref<T>(private val key: String, private val default: T) {
        private val flow = MutableStateFlow(default)
        // Snapshot-backed copy so reading `value` inside a composable re-composes when the setting changes.
        private var snap by mutableStateOf(default)
        val value: T get() = snap
        val state: StateFlow<T> get() = flow
        @Suppress("UNCHECKED_CAST")
        internal fun load() {
            flow.value = when (default) {
                is Boolean -> prefs.getBoolean(key, default) as T
                is Int -> prefs.getInt(key, default) as T
                is String -> (prefs.getString(key, default) ?: default) as T
                else -> default
            }
            snap = flow.value
        }
        fun set(v: T) {
            flow.value = v; snap = v
            prefs.edit().apply {
                when (v) {
                    is Boolean -> putBoolean(key, v)
                    is Int -> putInt(key, v)
                    is String -> putString(key, v)
                }
            }.apply()
        }
    }

    private val all = mutableListOf<Pref<*>>()
    private fun <T> pref(key: String, default: T) = Pref(key, default).also { all += it }

    val language = pref("hl", Locale.getDefault().language.ifBlank { "uk" })
    val region = pref("gl", Locale.getDefault().country.ifBlank { "UA" })
    val quality = pref("quality", AudioQuality.AUTO.name)
    val history = pref("history_enabled", true)
    val searchHistory = pref("search_history_enabled", true)
    val lrclib = pref("lrclib", false)
    /** Lyrics: true = the line fills progressively as it is sung, false = the whole line lights at once. */
    val lyricsFlow = pref("lyrics_flow", true)
    /** The "turn on synced lyrics" card in the lyrics panel was dismissed for good. */
    val lrclibHintHidden = pref("lrclib_hint_hidden", false)
    /** Lyrics timing nudge in ms (+ = later), for headphones or speakers that delay the sound. */
    val lyricsOffset = pref("lyrics_offset", 0)
    /** Ask YouTube for suggestions while typing a search (sends the unfinished text). */
    val searchSuggestions = pref("search_suggestions", true)
    /** Offline mode: the firewall lets nothing out; only downloaded and device files play. */
    val offline = pref("offline", false)
    /** Interface style: "ytm" (YouTube Music-like) or "minimal" (clean, Apple Music-like). */
    val uiStyle = pref("ui_style", "ytm")
    /** User-arranged page layouts (block order + hidden blocks), see ui/components/Layouts.kt. */
    val layoutHome = pref("layout_home", "")
    val layoutLibrary = pref("layout_library", "")
    val layoutSearch = pref("layout_search", "")
    val layoutTabs = pref("layout_tabs", "")
    val cacheMb = pref("cache_mb", 512)
    val persistQueue = pref("persist_queue", true)
    val autoplay = pref("autoplay", true)
    /** Look for a new version on GitHub once a day (Settings → About). */
    val updateCheck = pref("update_check", true)
    /** Last automatic check, in seconds since 1970. */
    val updateCheckedAt = pref("update_checked_at", 0)
    /** Keep playing after the app is swiped away from recent apps (off = closing the app stops the music). */
    val playAfterClose = pref("play_after_close", false)
    val skipSilence = pref("skip_silence", false)
    val accountHome = pref("account_home", false)
    val accountLibrary = pref("account_library", true)
    val accountSync = pref("account_sync", false)
    val stats = pref("stats_enabled", true)
    val uiLanguage = pref("ui_lang", "system")
    /** Devices nearby: encrypted link to the user's own computer, only on the local network (off = nothing sent or listened for). */
    val link = pref("link", false)
    val linkSync = pref("link_sync", true)
    val linkControl = pref("link_control", true)
    val linkSingle = pref("link_single", true)

    // Appearance
    val themeBg = pref("theme_bg", 0xFF000000.toInt())
    val themeAccent = pref("theme_accent", 0xFFFFFFFF.toInt())
    val themeImage = pref("theme_image", false)
    val themeVideo = pref("theme_video", false)
    val themeDim = pref("theme_dim", 60)
    val themeBlur = pref("theme_blur", 0)
    val themeCorner = pref("theme_corner", 4)
    val themeFont = pref("theme_font", 100)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        all.forEach { it.load() }
    }

    /** Plain settings for the encrypted backup (never includes account cookies — those live in a separate store). */
    fun dump(): org.json.JSONObject = org.json.JSONObject().apply {
        prefs.all.forEach { (k, v) -> if (v is Boolean || v is Int || v is String) put(k, v) }
    }

    fun restore(o: org.json.JSONObject) {
        val e = prefs.edit()
        o.keys().forEach { k -> when (val v = o.get(k)) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is String -> e.putString(k, v) } }
        e.commit()
        all.forEach { it.load() }
    }

    val audioQuality get() = runCatching { AudioQuality.valueOf(quality.value) }.getOrDefault(AudioQuality.AUTO)
}

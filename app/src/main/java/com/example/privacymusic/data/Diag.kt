package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import android.content.Context
import android.os.Build
import com.example.privacymusic.net.Firewall
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostics for bug reports. Nothing is sent automatically: the report is shown to the user, who
 * decides where (and whether) to send it. It contains no library data, no searches, no history, no account info.
 */
object Diag {
    private val events = ArrayDeque<String>()
    private lateinit var crashFile: File
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        crashFile = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText("${fmt.format(Date())} ${e.stackTraceToString().take(6000)}") }
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized fun log(what: String) {
        events.addLast("${fmt.format(Date())} ${what}")
        while (events.size > 40) events.removeFirst()
    }

    fun report(context: Context, userText: String, includeTech: Boolean): String = buildString {
        appendLine(userText.trim().ifBlank { T("(опис не додано)") })
        if (!includeTech) return@buildString
        appendLine(); appendLine(T("— Технічні дані (без особистої інформації) —"))
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("Privacy Music ${info?.versionName} (${info?.longVersionCode})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(T("Мова/регіон вмісту: {0}/{1}, якість: {2}", Settings.language.value, Settings.region.value, Settings.quality.value))
        appendLine(T("Мережа: дозволено {0}, заблоковано {1}", Firewall.allowedCount.value, Firewall.blocked.value.size))
        synchronized(this@Diag) { if (events.isNotEmpty()) { appendLine(T("Останні події:")); events.forEach { appendLine("  ${it}") } } }
        if (::crashFile.isInitialized && crashFile.exists()) { appendLine(T("Останній збій:")); appendLine(crashFile.readText().take(4000)) }
    }

    fun clearCrash() { if (::crashFile.isInitialized) crashFile.delete() }
}

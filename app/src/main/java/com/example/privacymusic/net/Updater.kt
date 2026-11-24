package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.privacymusic.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * "Check for updates": asks GitHub's public API for the latest release of the project (no account, no device id —
 * GitHub sees only the IP address), downloads its APK through the firewall and hands it to Android's installer.
 * The APK is signed with the same key, so Android only accepts a genuine update.
 */
object Updater {
    const val REPO = "CEOofFameIndustry/Privacy-Music"
    data class Release(val version: String, val notes: String, val apkUrl: String?, val size: Long, val sha256: String?, val page: String)

    /** Firewall rule: the release information and the release files of this one project, nothing else on GitHub. */
    fun allows(host: String, path: String, method: String): Boolean {
        if (method != "GET") return false
        return when (host) {
            "api.github.com" -> path == "/repos/${REPO}/releases/latest"
            "github.com" -> path.startsWith("/${REPO}/releases/download/")
            "objects.githubusercontent.com", "release-assets.githubusercontent.com" -> true // the file itself (after GitHub's redirect)
            else -> false
        }
    }
    /** GitHub may be contacted only while checking / downloading (set by the calls below). */
    @Volatile var active = false

    private fun parse(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.toIntOrNull() ?: -1 }.takeWhile { it >= 0 }
    fun newer(remote: String, local: String): Boolean {
        val a = parse(remote); val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The latest release, or null when there is none. Throws on network errors. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        active = true
        try {
            val req = Request.Builder().url("https://api.github.com/repos/${REPO}/releases/latest").header("Accept", "application/vnd.github+json").build()
            Firewall.client.newCall(req).execute().use { r ->
                if (r.code == 404) return@use null
                if (!r.isSuccessful) error("GitHub: HTTP ${r.code}")
                val j = JSONObject(r.body!!.string())
                val assets = j.optJSONArray("assets")
                var apk: JSONObject? = null
                for (i in 0 until (assets?.length() ?: 0)) { val a = assets!!.getJSONObject(i); if (a.optString("name").endsWith(".apk")) { apk = a; break } }
                Release(j.optString("tag_name").removePrefix("v"), j.optString("body").take(1200), apk?.optString("browser_download_url"),
                    apk?.optLong("size") ?: 0, apk?.optString("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"), j.optString("html_url"))
            }
        } finally { active = false }
    }

    /** Downloads the APK into the app's cache (progress 0..1), checking its SHA-256 when GitHub publishes one. */
    suspend fun download(context: Context, rel: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = rel.apkUrl ?: error("no apk")
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "PrivacyMusic.apk")
        active = true
        try {
            Firewall.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val total = r.body!!.contentLength().takeIf { it > 0 } ?: rel.size
                val md = MessageDigest.getInstance("SHA-256")
                r.body!!.byteStream().use { input -> file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); done += n; if (total > 0) progress(done.toFloat() / total) }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (rel.sha256 != null && !hex.equals(rel.sha256, true)) { file.delete(); error("checksum") }
            }
        } finally { active = false }
        file
    }

    /** Android asks once to allow installing from this app, then shows its own install screen. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Automatic check (once a day while the setting is on): returns a newer release, if any. */
    suspend fun autoCheck(context: Context): Release? {
        if (!Settings.updateCheck.value || Settings.offline.value) return null
        val now = System.currentTimeMillis()
        if (now - Settings.updateCheckedAt.value.toLong() * 1000 < 20 * 3600 * 1000L) return null
        Settings.updateCheckedAt.set((now / 1000).toInt())
        return runCatching { latest() }.getOrNull()?.takeIf { newer(it.version, installedVersion(context)) }
    }
}

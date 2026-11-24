package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encrypted backups. The file is encrypted on the phone with AES-256-GCM before it is handed to any
 * cloud app, so Google Drive / Proton Drive only ever store ciphertext. The 256-bit recovery key is shown to the
 * user to keep separately; without it the backup cannot be opened by anyone (including us).
 */
object Backup {
    private val MAGIC = "PMBACKUP1".toByteArray()
    private const val WRAP_ALIAS = "privacymusic_backup_wrap"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 32 symbols, no 0/O/1/I confusion

    private fun prefs(context: Context) = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun hasKey(context: Context) = prefs(context).contains("key")

    /** Creates a new random recovery key (replacing the old one) and returns it for the user to write down. */
    fun newKey(context: Context): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs(context).edit().putString("key", wrap(raw)).putBoolean("confirmed", false).apply()
        return format(raw)
    }

    fun currentKey(context: Context): String? = prefs(context).getString("key", null)?.let { runCatching { format(unwrap(it)) }.getOrNull() }
    fun confirmSaved(context: Context) = prefs(context).edit().putBoolean("confirmed", true).apply()
    fun isConfirmed(context: Context) = prefs(context).getBoolean("confirmed", false)
    fun lastBackup(context: Context) = prefs(context).getLong("last", 0L)

    /** Builds the encrypted backup file in the app's private cache, ready to be shared / saved. */
    fun create(context: Context): File {
        val raw = prefs(context).getString("key", null)?.let(::unwrap) ?: throw IOException(T("Спочатку створіть ключ відновлення"))
        val queue = File(context.filesDir, "queue.json").takeIf { it.exists() }?.readText()
        val payload = JSONObject().put("format", 1).put("created", System.currentTimeMillis())
            .put("library", Library.dump()).put("settings", Settings.dump()).put("queue", queue)
            .put("covers", Covers.dump())
            .put("background", Covers.bytesOf(File(context.filesDir, "theme/background.jpg")))
        val plain = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload.toString().toByteArray()) } }.toByteArray()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        val sealed = cipher.doFinal(plain)
        val dir = File(context.cacheDir, "backup").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(java.util.Date())
        return File(dir, "privacymusic-${stamp}.pmbackup").apply { writeBytes(MAGIC + nonce + sealed) }
            .also { prefs(context).edit().putLong("last", System.currentTimeMillis()).apply() }
    }

    /** Decrypts and applies a backup. Throws with a user-readable message on a wrong key or a damaged file. */
    fun restore(context: Context, bytes: ByteArray, keyText: String) {
        if (bytes.size < MAGIC.size + 12 + 16 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException(T("Це не файл резервної копії Privacy Music"))
        val raw = parse(keyText)
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        val plain = try { cipher.doFinal(bytes, MAGIC.size + 12, bytes.size - MAGIC.size - 12) } catch (_: AEADBadTagException) {
            throw IOException(T("Неправильний ключ відновлення або файл пошкоджено"))
        }
        val json = JSONObject(GZIPInputStream(plain.inputStream()).use { String(it.readBytes()) })
        Library.restore(json.getJSONObject("library"))
        Covers.restore(context, json.optJSONObject("covers"))
        json.optString("background").takeUnless { it.isEmpty() || it == "null" }?.let {
            File(context.filesDir, "theme").mkdirs(); File(context.filesDir, "theme/background.jpg").writeBytes(Covers.toBytes(it))
        }
        json.optJSONObject("settings")?.let(Settings::restore)
        val queue = json.optString("queue").takeUnless { it.isEmpty() || it == "null" }
        val qf = File(context.filesDir, "queue.json"); if (queue != null) qf.writeText(queue) else qf.delete()
        // Remember the key that worked so the next backup uses it.
        prefs(context).edit().putString("key", wrap(raw)).putBoolean("confirmed", true).apply()
    }

    // ---------- key text format: 52 Base32 symbols in groups of 4 ----------
    private fun format(raw: ByteArray): String {
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (b in raw) { buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8; while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    private fun parse(text: String): ByteArray {
        val clean = text.uppercase().filter { it in ALPHABET }
        if (clean.length != 52) throw IOException(T("Ключ має містити 52 символи (зараз {0})", clean.length))
        val out = ByteArrayOutputStream(); var buffer = 0; var bits = 0
        for (c in clean) { buffer = (buffer shl 5) or ALPHABET.indexOf(c); bits += 5; if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 } }
        return out.toByteArray().copyOf(32)
    }

    // ---------- the key is kept on the phone wrapped by a hardware-backed Keystore key ----------
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP)
    }
    private fun unwrap(blob: String): ByteArray {
        val (iv, data) = blob.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }
}

package com.example.privacymusic.account

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional sign-in for people with a YouTube Music (Premium) account. No OAuth, no Google SDK, no Play Services:
 * the user signs in on Google's own page inside an isolated WebView, we keep only the session cookies that the
 * music.youtube.com API needs, encrypt them with a hardware-backed Android Keystore key, and wipe the WebView.
 */
object AccountStore {
    private const val KEY_ALIAS = "privacymusic_account"
    private lateinit var prefs: SharedPreferences
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()
    @Volatile private var cookies: String? = null

    /** Only the cookies required to authenticate API calls. Tracking/visitor cookies are dropped. */
    private val needed = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID", "SIDCC", "LOGIN_INFO",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
        "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        cookies = prefs.getString("blob", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        if (cookies == null) prefs.edit().remove("blob").apply()
        _signedIn.value = sapisid() != null
    }

    fun filter(raw: String): String = raw.split(';').map { it.trim() }
        .filter { c -> c.substringBefore('=') in needed && c.contains('=') }
        .distinctBy { it.substringBefore('=') }
        .joinToString("; ")

    fun save(rawCookies: String): Boolean {
        val clean = filter(rawCookies)
        if (!clean.split("; ").any { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }) return false
        cookies = clean
        prefs.edit().putString("blob", encrypt(clean)).apply()
        _signedIn.value = true
        return true
    }

    fun cookieHeader(): String? = cookies
    fun sapisid(): String? = cookies?.split("; ")?.firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
        ?.substringAfter('=')

    fun signOut() {
        cookies = null
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        wipeWebView()
        _signedIn.value = false
    }

    fun wipeWebView() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (iv, data) = blob.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }
}

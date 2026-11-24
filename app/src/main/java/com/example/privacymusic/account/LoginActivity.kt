package com.example.privacymusic.account

import com.example.privacymusic.i18n.T

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.ByteArrayInputStream

/**
 * Sign-in on Google's own page, in a throw-away WebView:
 *  - nothing is loaded from music.youtube.com itself (its web app would run Google's telemetry with your account);
 *    the moment the login redirects there we grab the session cookies and stop;
 *  - analytics / logging hosts are blocked inside the WebView;
 *  - no Safe Browsing, no WebView metrics, no autofill, no saved form data;
 *  - all WebView cookies, storage and cache are wiped before and after.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var done = false

    private val blockedHosts = listOf(
        "play.google.com", "www.google-analytics.com", "google-analytics.com", "www.googletagmanager.com",
        "ssl.google-analytics.com", "stats.g.doubleclick.net", "doubleclick.net", "googleads.g.doubleclick.net",
        "csp.withgoogle.com", "jnn-pa.googleapis.com", "content-autofill.googleapis.com", "ogs.google.com",
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.wipeWebView()
        val root = FrameLayout(this)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION") v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.settings.apply {
            javaScriptEnabled = true // Google's sign-in page doesn't work without it
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION") saveFormData = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        if (Build.VERSION.SDK_INT >= 26) web.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = android.view.View.VISIBLE
                if (intercept(Uri.parse(url))) view.stopLoading()
            }
            override fun onPageFinished(view: WebView, url: String) { progress.visibility = android.view.View.GONE }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host.orEmpty()
                val path = request.url.path.orEmpty()
                val block = blockedHosts.any { host == it || host.endsWith(".${it}") } || path.contains("/log") && host.endsWith("google.com") ||
                    (host == "music.youtube.com")
                return if (block) WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        web.loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
            Uri.encode("https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=https%3A%2F%2Fmusic.youtube.com%2F"))
    }

    private fun intercept(url: Uri): Boolean {
        if (done || url.host != "music.youtube.com") return false
        val raw = listOfNotNull(
            CookieManager.getInstance().getCookie("https://music.youtube.com"),
            CookieManager.getInstance().getCookie("https://www.youtube.com"),
        ).joinToString("; ")
        if (AccountStore.save(raw)) {
            done = true
            Toast.makeText(this, T("Акаунт підключено"), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
        return true
    }

    override fun onDestroy() {
        runCatching {
            web.stopLoading(); web.clearHistory(); web.clearFormData(); web.clearCache(true); web.destroy()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        }
        AccountStore.wipeWebView()
        super.onDestroy()
    }
}

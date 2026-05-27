package com.example.sleppify

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.animation.AnimationUtils

class YouTubeMusicWebSessionActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var webSessionContainer: android.widget.FrameLayout? = null
    private var loginOverlay: View? = null
    private var btnOverlayLogin: Button? = null
    private var lastCookieHeader: String = ""
    private var lastAutoLoginClickAtMs: Long = 0L
    private var autoConnectTriggered: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_web_session)

        webSessionContainer = findViewById(R.id.webSessionContainer)
        loginOverlay = findViewById(R.id.loginOverlay)
        btnOverlayLogin = findViewById(R.id.btnOverlayLogin)

        val imgAmbient = findViewById<ImageView>(R.id.imgAmbient)
        imgAmbient?.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                val breatheAnim = AnimationUtils.loadAnimation(this, R.anim.bg_breathe)
                imgAmbient.startAnimation(breatheAnim)
            } catch (e: Exception) {
                Log.e("YouTubeMusicWebSession", "Error loading breathe animation", e)
            }
        }, 400L)

        btnOverlayLogin?.setOnClickListener {
            // Start loading WebView only when user taps — avoids lag on the onboarding overlay
            initWebViewAndLoad()
            loginOverlay?.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewAndLoad() {
        val wv = WebView(this)
        webView = wv
        webSessionContainer?.addView(wv, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val defaultUserAgent = settings.userAgentString
        if (!defaultUserAgent.isNullOrBlank()) {
            val cleanUserAgent = defaultUserAgent
                .replace("Version/4.0", "")
                .replace("; wv", "")
                .replace("  ", " ")
                .trim()
            settings.userAgentString = cleanUserAgent
        }

        wv.webViewClient = object : WebViewClient() {
            private fun isAllowedHost(host: String): Boolean {
                val lowerHost = host.lowercase()
                return lowerHost.contains("google") || 
                       lowerHost.contains("youtube") || 
                       lowerHost.contains("gstatic")
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                val host = uri.host.orEmpty()
                return !isAllowedHost(host)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return !isAllowedHost(host)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                attemptAutoLoginClick()
                refreshSessionState()
            }
        }

        wv.loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F")
    }

    private fun refreshSessionState() {
        val currentWebView = webView ?: return
        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com/")

        if (cookie.isNullOrBlank()) {
            lastCookieHeader = ""
            return
        }

        if (looksLikeAuthenticatedCookie(cookie)) {
            lastCookieHeader = cookie
            Log.i(TAG_STREAMING_WEB, "authenticated_cookie_detected length=" + cookie.length)
            if (!autoConnectTriggered) {
                autoConnectTriggered = true
                completeWithSession()
            }
            return
        }

        lastCookieHeader = ""
    }

    private fun attemptAutoLoginClick() {
        val currentWebView = webView ?: return

        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com/")
        if (!cookie.isNullOrBlank() && looksLikeAuthenticatedCookie(cookie)) {
            return
        }

        val now = System.currentTimeMillis()
        if ((now - lastAutoLoginClickAtMs) < 1200L) {
            return
        }
        lastAutoLoginClickAtMs = now

        val script = buildString {
            append("(function(){")
            append("try {")
            append("var nodes = Array.prototype.slice.call(document.querySelectorAll('a,button,tp-yt-paper-button,yt-formatted-string'));")
            append("for (var i = 0; i < nodes.length; i++) {")
            append("var el = nodes[i];")
            append("var txt = ((el.innerText || el.textContent || '') + '').toLowerCase();")
            append("var href = (el.getAttribute && el.getAttribute('href')) || '';")
            append("var maybeLogin = txt.indexOf('iniciar sesion') >= 0")
            append(" || txt.indexOf('iniciar sesión') >= 0")
            append(" || txt.indexOf('inicia sesion') >= 0")
            append(" || txt.indexOf('inicia sesión') >= 0")
            append(" || txt.indexOf('sign in') >= 0")
            append(" || txt.indexOf('acceder') >= 0")
            append(" || href.indexOf('ServiceLogin') >= 0")
            append(" || href.indexOf('signin') >= 0;")
            append("if (maybeLogin) { el.click(); return 'clicked'; }")
            append("}")
            append("return 'none';")
            append("} catch (e) { return 'error'; }")
            append("})();")
        }

        currentWebView.evaluateJavascript(script, null)
    }

    private fun looksLikeAuthenticatedCookie(cookieHeader: String): Boolean {
        val lower = cookieHeader.lowercase()
        return lower.contains("sapisid=")
            || lower.contains("__secure-3papisid=")
            || lower.contains("ssid=")
            || lower.contains("sid=")
    }

    private fun completeWithSession() {
        if (TextUtils.isEmpty(lastCookieHeader)) {
            refreshSessionState()
            if (TextUtils.isEmpty(lastCookieHeader)) {
                return
            }
        }

        // Persist the cookie directly so that if the calling MainActivity is
        // killed by the system while this activity is in the foreground, the
        // recreated MusicPlayerFragment will find the session in SharedPreferences
        // and won't launch the WebView again.
        getSharedPreferences("player_state", MODE_PRIVATE)
            .edit()
            .putString("stream_last_youtube_web_cookie", lastCookieHeader)
            .commit()

        // Trigger deferred heavy initialization now that login succeeded.
        (applicationContext as? SleppifyApp)?.performDeferredInit()

        val data = Intent()
        data.putExtra(EXTRA_SESSION_COOKIE_HEADER, lastCookieHeader)

        var userAgent = ""
        val settings = webView?.settings
        if (settings?.userAgentString != null) {
            userAgent = settings.userAgentString
        }

        Log.i(TAG_STREAMING_WEB, "session_completed cookieLength=" + lastCookieHeader.length)
        data.putExtra(EXTRA_SESSION_USER_AGENT, userAgent)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_COOKIE_HEADER = "extra_session_cookie_header"
        const val EXTRA_SESSION_USER_AGENT = "extra_session_user_agent"
        private const val TAG_STREAMING_WEB = "SleppifyStreamingWeb"
    }
}

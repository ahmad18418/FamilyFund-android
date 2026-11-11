package com.familyfund.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
    }

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var lockPanel: View
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private var unlocked = false

    private val appVersionLabel: String
        get() = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        webView = findViewById(R.id.webView)
        lockPanel = findViewById(R.id.lockPanel)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        findViewById<TextView>(R.id.versionText)?.text = appVersionLabel

        findViewById<MaterialButton>(R.id.btnUnlock).setOnClickListener {
            if (prefs.biometricEnabled) startBiometric() else unlockAndLoad()
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupWebView()

        if (prefs.biometricEnabled && canUseBiometric()) {
            showLock("برای ورود اثرانگشت یا چهره را تأیید کنید\n$appVersionLabel")
            startBiometric()
        } else {
            unlockAndLoad()
        }
    }

    override fun onPause() {
        super.onPause()
        persistSession()
    }

    override fun onResume() {
        super.onResume()
    }


    private fun cookieUrls(): List<String> {
        val base = prefs.serverUrl.trimEnd('/')
        val hostOnly = try {
            val u = Uri.parse(if (base.contains("://")) base else "http://$base")
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            base.substringBefore("/fund").ifBlank { base }
        }
        return listOf(
            base,
            "$base/",
            "$base/login",
            "$base/dashboard",
            hostOnly,
            "$hostOnly/",
            "$hostOnly/fund",
            "$hostOnly/fund/"
        ).distinct()
    }

    private fun persistSession() {
        try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val parts = linkedSetOf<String>()
            for (url in cookieUrls()) {
                val c = cm.getCookie(url)
                if (!c.isNullOrBlank()) {
                    c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { parts.add(it) }
                }
            }
            if (parts.isNotEmpty()) {
                prefs.savedCookies = parts.joinToString("; ")
            }
            if (this::webView.isInitialized) {
                val u = webView.url
                if (!u.isNullOrBlank() && !u.contains("/login") && !u.contains("/logout")) {
                    prefs.lastUrl = u
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun clearWebSession() {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cm.removeAllCookies(null)
                cm.flush()
            } else {
                @Suppress("DEPRECATION")
                cm.removeAllCookie()
            }
            prefs.savedCookies = ""
            prefs.lastUrl = ""
        } catch (_: Exception) {
        }
    }

    private fun restoreCookies() {
        try {
            val saved = prefs.savedCookies
            if (saved.isBlank()) return
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val pairs = saved.split(";").map { it.trim() }.filter { it.contains("=") }
            for (url in cookieUrls()) {
                for (pair in pairs) {
                    // Path را صریح می‌گذاریم تا زیر /fund هم اعمال شود
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=").trim()
                    if (name.isBlank()) continue
                    cm.setCookie(url, "$name=$value; Path=/")
                    cm.setCookie(url, "$name=$value; Path=/fund")
                }
            }
            cm.flush()
        } catch (_: Exception) {
        }
    }

    private fun unlockAndLoad() {
        unlocked = true
        lockPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        restoreCookies()
        val extra = intent?.getStringExtra(EXTRA_OPEN_URL)
        val base = prefs.serverUrl.trimEnd('/')
        val hasSession = prefs.savedCookies.contains("family_fund_session") ||
            prefs.savedCookies.contains("family_fund_remember") ||
            prefs.savedCookies.contains("session")
        val url = when {
            !extra.isNullOrBlank() -> extra
            hasSession && prefs.lastUrl.isNotBlank() && !prefs.lastUrl.contains("/login") -> prefs.lastUrl
            hasSession -> "$base/dashboard"
            else -> "$base/login"
        }
        progress.visibility = View.VISIBLE
        // بعد از flush کوکی، صفحه را بارگذاری کن
        val cm = CookieManager.getInstance()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.flush()
            webView.postDelayed({
                webView.loadUrl(url)
            }, 120)
        } else {
            webView.loadUrl(url)
        }
        UpdateChecker.check(this, prefs.serverUrl)
        MessageNotifier.startPolling(this, prefs.serverUrl)
    }

    private fun showLock(msg: String) {
        unlocked = false
        lockPanel.visibility = View.VISIBLE
        webView.visibility = View.GONE
        statusText.text = msg
    }


    private fun canUseBiometric(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun startBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockAndLoad()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        showLock("برای ورود اثرانگشت را تأیید کنید یا تنظیمات را باز کنید\n$appVersionLabel")
                    } else {
                        showLock("خطا: $errString\n$appVersionLabel")
                    }
                }

                override fun onAuthenticationFailed() {
                    // بگذار کاربر دوباره تلاش کند
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود به صندوق خانواده")
            .setSubtitle("تأیید هویت")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }



    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (unlocked) {
            val extra = intent?.getStringExtra(EXTRA_OPEN_URL)
            if (!extra.isNullOrBlank()) {
                webView.loadUrl(extra)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        // حفظ نشست بعد از بستن اپ
        restoreCookies()

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " FamilyFundApp/${BuildConfig.VERSION_NAME}"
        webView.addJavascriptInterface(AppBridge(), "FamilyFundApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.contains("/logout")) {
                    clearWebSession()
                }
                // لینک‌های داخلی صندوق در WebView بمانند
                if (u.contains("130.185.75.146") || u.contains(prefs.serverUrl.removePrefix("http://").removePrefix("https://").substringBefore("/"))) {
                    return false
                }
                // بقیه را در مرورگر باز نکن مگر لازم باشد
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                try {
                    val u = url ?: ""
                    // فقط بعد از خروج واقعی کوکی را پاک کن — نه با هر بار دیدن صفحه لاگین
                    // (در غیر این صورت با یک ریدایرکت موقت همه نشست از بین می‌رود)
                    if (u.contains("/logout")) {
                        clearWebSession()
                    } else if (u.isNotBlank() && !u.contains("/login")) {
                        prefs.lastUrl = u
                        val c = CookieManager.getInstance().getCookie(prefs.serverUrl)
                            ?: CookieManager.getInstance().getCookie(prefs.serverUrl.trimEnd('/') + "/")
                        if (!c.isNullOrBlank()) prefs.savedCookies = c
                    }
                } catch (_: Exception) {}
                // تزریق نسخه اپ به همه صفحات وب
                val js = """
                    (function(){
                      window.__FF_APP = {
                        isAndroid: true,
                        versionName: '${BuildConfig.VERSION_NAME}',
                        versionCode: ${BuildConfig.VERSION_CODE}
                      };
                      window.FamilyFundApp = window.FamilyFundApp || {};
                      try {
                        document.dispatchEvent(new CustomEvent('ff-app-ready', {detail: window.__FF_APP}));
                      } catch(e) {}
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }
        webView.webChromeClient = WebChromeClient()

        // دانلود APK از داخل WebView (لینک‌های دانلود در صفحات وب)
        webView.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            if (url.contains("download/android") || (mimeType != null && mimeType.contains("android.package")) || url.endsWith(".apk")) {
                UpdateChecker.downloadAndInstall(this@MainActivity, url)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
        })
    }

    inner class AppBridge {
        @JavascriptInterface
        fun getVersionName(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun getVersionCode(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "در حال بررسی نسخه…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                UpdateChecker.check(this@MainActivity, prefs.serverUrl, true)
            }
        }

        @JavascriptInterface
        fun downloadUpdate(apkUrl: String) {
            runOnUiThread {
                if (apkUrl.isNullOrBlank()) {
                    UpdateChecker.check(this@MainActivity, prefs.serverUrl, true)
                } else {
                    UpdateChecker.downloadAndInstall(this@MainActivity, apkUrl)
                }
            }
        }
    }
}

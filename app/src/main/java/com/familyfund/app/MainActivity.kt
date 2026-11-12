package com.familyfund.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
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
    private var loginRecoveryTried = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appVersionLabel: String
        get() = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private val baseUrl: String
        get() = prefs.serverUrl.trimEnd('/')

    private val hostRoot: String
        get() {
            return try {
                val u = Uri.parse(if (baseUrl.contains("://")) baseUrl else "http://$baseUrl")
                "${u.scheme}://${u.host}"
            } catch (_: Exception) {
                baseUrl.substringBefore("/fund").ifBlank { baseUrl }
            }
        }

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
        persistSession(force = true)
    }

    override fun onStop() {
        super.onStop()
        persistSession(force = true)
        // خروج از اپ ≠ خروج از حساب: نوتیف پس‌زمینه را نگه دار
        try {
            if (prefs.savedCookies.isNotBlank() || prefs.hasCredentials()) {
                MessageNotifier.scheduleBackground(applicationContext)
                MessageNotifier.scheduleImmediate(applicationContext)
            }
        } catch (_: Exception) {}
    }

    private fun cookieTargetUrls(): List<String> {
        val b = baseUrl
        val h = hostRoot
        return listOf(
            h,
            "$h/",
            b,
            "$b/",
            "$b/login",
            "$b/dashboard",
            "$h/fund",
            "$h/fund/",
            "$h/fund/login",
            "$h/fund/dashboard"
        ).distinct()
    }

    /** جمع‌آوری همه کوکی‌های مرتبط با سرور صندوق */
    private fun collectCookiesFromWebView(): String {
        val cm = CookieManager.getInstance()
        val parts = linkedSetOf<String>()
        for (url in cookieTargetUrls()) {
            try {
                val c = cm.getCookie(url)
                if (!c.isNullOrBlank()) {
                    c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { parts.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return parts.joinToString("; ")
    }

    private fun hasSessionCookie(raw: String = prefs.savedCookies): Boolean {
        val s = raw.lowercase()
        return s.contains("family_fund_session=") ||
            s.contains("family_fund_remember=") ||
            s.contains("remember_token=") ||
            (s.contains("session=") && !s.contains("family_fund_session="))
    }

    private fun persistSession(force: Boolean = false) {
        try {
            val cm = CookieManager.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush()
            }
            val collected = collectCookiesFromWebView()
            if (collected.isNotBlank()) {
                // ادغام با قبلی تا کوکی‌های قبلی از دست نروند
                val merged = linkedSetOf<String>()
                prefs.savedCookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { merged.add(it) }
                collected.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { merged.add(it) }
                prefs.savedCookies = merged.joinToString("; ")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.removeAllCookies(null)
                cm.flush()
            } else {
                @Suppress("DEPRECATION")
                cm.removeAllCookie()
            }
            prefs.savedCookies = ""
            prefs.lastUrl = ""
            prefs.clearCredentials()
            loginRecoveryTried = false
        } catch (_: Exception) {
        }
    }

    private fun restoreCookies() {
        try {
            val saved = prefs.savedCookies
            if (saved.isBlank()) return
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true)
            }
            val pairs = saved.split(";").map { it.trim() }.filter { it.contains("=") }
            for (url in cookieTargetUrls()) {
                for (pair in pairs) {
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=").trim()
                    if (name.isBlank()) continue
                    // بدون و با Path — سازگاری بیشتر
                    cm.setCookie(url, "$name=$value")
                    cm.setCookie(url, "$name=$value; Path=/")
                    cm.setCookie(url, "$name=$value; Path=/fund")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun targetUrlAfterUnlock(): String {
        val extra = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!extra.isNullOrBlank()) return extra
        if (hasSessionCookie()) {
            val last = prefs.lastUrl
            if (last.isNotBlank() && !last.contains("/login") && !last.contains("/logout")) {
                return last
            }
            return "$baseUrl/dashboard"
        }
        return "$baseUrl/login"
    }

    private fun unlockAndLoad() {
        unlocked = true
        loginRecoveryTried = false
        lockPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        restoreCookies()

        val url = targetUrlAfterUnlock()
        // کمی صبر تا CookieManager.flush اعمال شود
        mainHandler.postDelayed({
            restoreCookies()
            webView.loadUrl(url)
        }, 250)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true)
        }
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
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                try {
                    val u = url ?: ""
                    if (u.contains("/logout")) {
                        clearWebSession()
                        return
                    }
                    // همیشه بعد از هر صفحه کوکی را ذخیره کن (به‌خصوص بعد از لاگین)
                    persistSession(force = true)

                    // اگر بعد از اثرانگشت به لاگین افتادیم: اول کوکی، بعد ورود خودکار با مشخصات ذخیره‌شده
                    if (unlocked && u.contains("/login") && !loginRecoveryTried) {
                        loginRecoveryTried = true
                        mainHandler.postDelayed({
                            if (hasSessionCookie()) {
                                restoreCookies()
                                webView.loadUrl("$baseUrl/dashboard")
                            } else if (prefs.hasCredentials()) {
                                autoSubmitLogin()
                            }
                        }, 400)
                        return
                    }
                    // روی صفحه لاگین: ذخیره مشخصات هنگام ارسال فرم + تبدیل ارقام فارسی
                    if (u.contains("/login")) {
                        injectLoginHelpers()
                    }
                    // ورود موفق: پرچم بازیابی را ریست کن
                    if (u.contains("/dashboard") || (!u.contains("/login") && u.contains("/fund"))) {
                        loginRecoveryTried = false
                    }

                    // تزریق پل اندروید
                    val js = """
                        (function(){
                          window.__FF_APP = {
                            versionName: '${BuildConfig.VERSION_NAME}',
                            versionCode: ${BuildConfig.VERSION_CODE},
                            isNative: true
                          };
                          window.FamilyFundApp = window.FamilyFundApp || {};
                          try {
                            document.dispatchEvent(new CustomEvent('ff-app-ready', {detail: window.__FF_APP}));
                          } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                } catch (_: Exception) {
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        webView.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            if (url.contains("download/android") ||
                (mimeType != null && mimeType.contains("android.package")) ||
                url.endsWith(".apk")
            ) {
                UpdateChecker.downloadAndInstall(this@MainActivity, url)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
        })
    }


    private fun injectLoginHelpers() {
        val js = """
            (function(){
              function toEn(s){
                if(!s) return s;
                var p='۰۱۲۳۴۵۶۷۸۹';
                var a='٠١٢٣٤٥٦٧٨٩';
                var out='';
                for(var i=0;i<s.length;i++){
                  var c=s.charAt(i);
                  var j=p.indexOf(c);
                  if(j>=0){ out+=String(j); continue; }
                  j=a.indexOf(c);
                  if(j>=0){ out+=String(j); continue; }
                  out+=c;
                }
                return out;
              }
              var form=document.querySelector('form');
              if(!form || form.__ffBound) return;
              form.__ffBound=true;
              form.addEventListener('submit', function(){
                try{
                  var codeEl=form.querySelector('[name=membership_number]');
                  var passEl=form.querySelector('[name=password]');
                  if(codeEl){ codeEl.value=toEn(codeEl.value); }
                  if(passEl){ passEl.value=toEn(passEl.value); }
                  if(window.FamilyFundApp && FamilyFundApp.saveCredentials){
                    FamilyFundApp.saveCredentials(codeEl?codeEl.value:'', passEl?passEl.value:'');
                  }
                }catch(e){}
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun autoSubmitLogin() {
        val code = prefs.savedUserCode.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
        val pass = prefs.savedPassword.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
        if (code.isBlank() || pass.isBlank()) return
        val js = """
            (function(){
              var form=document.querySelector('form');
              if(!form) return;
              var codeEl=form.querySelector('[name=membership_number]');
              var passEl=form.querySelector('[name=password]');
              if(!codeEl||!passEl) return;
              codeEl.value='$code';
              passEl.value='$pass';
              form.submit();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class AppBridge {
        @JavascriptInterface
        fun saveCredentials(code: String?, password: String?) {
            val c = (code ?: "").trim()
            val p = password ?: ""
            if (c.isNotBlank() && p.isNotBlank()) {
                prefs.savedUserCode = c
                prefs.savedPassword = p
            }
        }

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

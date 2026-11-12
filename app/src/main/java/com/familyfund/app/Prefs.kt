package com.familyfund.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("family_fund_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, BuildConfig.DEFAULT_SERVER_URL)
            ?.trim()
            ?.trimEnd('/')
            ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = sp.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var biometricEnabled: Boolean
        get() = sp.getBoolean(KEY_BIO, true)
        set(value) = sp.edit().putBoolean(KEY_BIO, value).apply()

    var lastUrl: String
        get() = sp.getString(KEY_LAST_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_URL, value).apply()

    var savedCookies: String
        get() = sp.getString(KEY_COOKIES, "") ?: ""
        set(value) = sp.edit().putString(KEY_COOKIES, value).apply()

    /** برای ورود خودکار بعد از اثرانگشت وقتی کوکی از بین رفته */
    var savedUserCode: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(value) = sp.edit().putString(KEY_USER, value).apply()

    var savedPassword: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASS, value).apply()

    fun clearCredentials() {
        sp.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    fun hasCredentials(): Boolean =
        savedUserCode.isNotBlank() && savedPassword.isNotBlank()

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_BIO = "biometric_enabled"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_COOKIES = "saved_cookies"
        private const val KEY_USER = "saved_user_code"
        private const val KEY_PASS = "saved_password"
    }
}

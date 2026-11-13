package com.familyfund.app

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import java.net.URI

object CookieHeader {
    fun refreshFromWebView(ctx: Context) {
        try {
            val cm = CookieManager.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush()
            }
            val cookie = fromWebView(ctx)
            if (cookie.isNotBlank()) {
                Prefs(ctx).savedCookies = cookie
            }
        } catch (_: Exception) {
        }
    }

    fun from(ctx: Context): String {
        val prefs = Prefs(ctx)
        val saved = prefs.savedCookies.trim()
        val live = fromWebView(ctx)
        if (live.isNotBlank()) {
            if (live != saved) prefs.savedCookies = live
            return live
        }
        return saved
    }

    private fun fromWebView(ctx: Context): String {
        return try {
            val cm = CookieManager.getInstance()
            val base = Prefs(ctx).serverUrl.trimEnd('/')
            val host = try {
                val u = URI(base)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) {
                base
            }
            val parts = linkedSetOf<String>()
            for (url in listOf(base, "$base/", host, "$host/", "$host/fund", "$host/fund/")) {
                val c = cm.getCookie(url)
                if (!c.isNullOrBlank()) {
                    c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { parts.add(it) }
                }
            }
            parts.joinToString("; ")
        } catch (_: Exception) {
            ""
        }
    }
}

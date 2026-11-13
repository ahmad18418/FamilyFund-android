package com.familyfund.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * بررسی پیام‌های خوانده‌نشده حتی وقتی اپ بسته است.
 * کوکی ذخیره‌شده یا ورود مجدد با کد/رمز ذخیره‌شده.
 */
class MessageCheckWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            val prefs = Prefs(applicationContext)
            val base = prefs.serverUrl.trimEnd('/')
            if (base.isBlank()) return Result.success()

            var cookie = resolveCookie(prefs)
            if (cookie.isBlank()) {
                cookie = tryLogin(prefs, base) ?: return Result.success()
            }

            var json = fetchUnread(base, cookie)
            if (json == null) {
                // سشن منقضی → ورود مجدد
                cookie = tryLogin(prefs, base) ?: return Result.retry()
                json = fetchUnread(base, cookie) ?: return Result.retry()
            }

            val count = json.optInt("count", 0)
            if (count <= 0) return Result.success()

            val arr = json.optJSONArray("messages")
            var title = "پیام جدید صندوق"
            var text = "$count پیام خوانده‌نشده"
            var newestId = 0
            if (arr != null && arr.length() > 0) {
                val first = arr.getJSONObject(0)
                newestId = first.optInt("id", 0)
                val t = first.optString("title", "").trim()
                val b = first.optString("body", "").trim()
                if (t.isNotBlank()) title = t
                if (b.isNotBlank()) text = b
            }

            val sp = applicationContext.getSharedPreferences("ff_msg_notify", Context.MODE_PRIVATE)
            val last = sp.getInt("last_notif_id", 0)
            val lastCount = sp.getInt("last_notif_count", 0)
            // اگر پیام جدیدتر یا تعداد بیشتر شد نوتیف بده
            if (newestId > 0 && newestId <= last && count <= lastCount) {
                return Result.success()
            }
            if (newestId > 0) sp.edit().putInt("last_notif_id", newestId).apply()
            sp.edit().putInt("last_notif_count", count).apply()

            showNotification(base, title, text, count)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun resolveCookie(prefs: Prefs): String {
        // اول کوکی پایدار Prefs (کار می‌کند وقتی WebView مرده)
        var c = prefs.savedCookies.trim()
        if (c.isNotBlank()) return c
        try {
            CookieHeader.refreshFromWebView(applicationContext)
            c = CookieHeader.from(applicationContext)
            if (c.isNotBlank()) {
                prefs.savedCookies = c
            }
        } catch (_: Exception) {
        }
        return c
    }

    private fun fetchUnread(base: String, cookie: String): JSONObject? {
        val conn = (URL("$base/api/messages/unread").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "FamilyFundApp-Worker/1.0")
        }
        val code = conn.responseCode
        if (code == 302 || code == 401 || code == 403) {
            conn.disconnect()
            return null
        }
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    /** ورود فرم و برگرداندن Cookie جلسه‌ای */
    private fun tryLogin(prefs: Prefs, base: String): String? {
        if (!prefs.hasCredentials()) return null
        return try {
            val user = prefs.savedUserCode
            val pass = prefs.savedPassword
            val form = "membership_number=${URLEncoder.encode(user, "UTF-8")}" +
                "&password=${URLEncoder.encode(pass, "UTF-8")}"
            val conn = (URL("$base/login").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "text/html,application/json")
                setRequestProperty("User-Agent", "FamilyFundApp-Worker/1.0")
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val headers = conn.headerFields
            conn.disconnect()
            val cookies = mutableListOf<String>()
            headers?.forEach { (k, vals) ->
                if (k != null && k.equals("Set-Cookie", true)) {
                    vals?.forEach { raw ->
                        val part = raw.substringBefore(";").trim()
                        if (part.contains("=")) cookies.add(part)
                    }
                }
            }
            if (cookies.isEmpty() && code !in listOf(302, 200, 301)) return null
            val cookieStr = cookies.joinToString("; ")
            if (cookieStr.isNotBlank()) {
                prefs.savedCookies = cookieStr
            }
            cookieStr.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun showNotification(base: String, title: String, text: String, count: Int) {
        ensureChannel()
        val open = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_URL, "$base/messages")
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 2002, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setNumber(count)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(9002, notif)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "پیام‌های صندوق",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پیام‌های جدید حتی وقتی برنامه بسته است"
                enableVibration(true)
                setShowBadge(true)
            }
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL = "family_fund_messages"
        const val UNIQUE = "family_fund_msg_check"
    }
}

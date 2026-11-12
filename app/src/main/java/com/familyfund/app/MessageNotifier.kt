package com.familyfund.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object MessageNotifier {
    private const val CHANNEL_ID = "family_fund_messages"
    private const val PREF = "ff_msg_notify"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // حذف کانال قدیمی و ساخت مجدد با اهمیت بالا
            try { nm.deleteNotificationChannel(CHANNEL_ID) } catch (_: Exception) {}
            val ch = NotificationChannel(
                CHANNEL_ID,
                "پیام‌های صندوق",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پیام‌های جدید صندوق خانواده"
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4401
                )
            }
        }
    }

    fun scheduleImmediate(ctx: Context) {
        try {
            CookieHeader.refreshFromWebView(ctx)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = androidx.work.OneTimeWorkRequestBuilder<MessageCheckWorker>()
                .setConstraints(constraints)
                .addTag("msg_immediate")
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        } catch (_: Exception) {}
    }

    fun scheduleBackground(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            MessageCheckWorker.UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    fun startPolling(activity: MainActivity, serverBase: String) {
        ensureChannel(activity)
        requestPermissionIfNeeded(activity)
        scheduleBackground(activity.applicationContext)
        // ذخیره کوکی فعلی از WebView
        try {
            (activity as? MainActivity)?.let { }
            CookieHeader.refreshFromWebView(activity)
        } catch (_: Exception) {}

        io.execute {
            try {
                val base = serverBase.trimEnd('/')
                val cookie = CookieHeader.from(activity)
                val conn = (URL("$base/api/heartbeat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
        if (scheduled) return
        scheduled = true
        val runnable = object : Runnable {
            override fun run() {
                CookieHeader.refreshFromWebView(activity)
                poll(activity, serverBase)
                main.postDelayed(this, 30_000L)
            }
        }
        main.postDelayed(runnable, 5_000L)
    }

    private fun poll(activity: MainActivity, serverBase: String) {
        io.execute {
            try {
                val base = serverBase.trimEnd('/')
                val cookie = CookieHeader.from(activity)
                if (cookie.isBlank()) return@execute
                val conn = (URL("$base/api/messages/unread").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "FamilyFundApp/${BuildConfig.VERSION_NAME}")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    return@execute
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val count = json.optInt("count", 0)
                if (count <= 0) return@execute

                val arr = json.optJSONArray("messages")
                val sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                val lastNotifiedId = sp.getInt("last_notif_id", 0)
                var newestId = 0
                var title = "پیام جدید صندوق"
                var text = "$count پیام خوانده‌نشده"
                if (arr != null && arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    newestId = first.optInt("id", 0)
                    title = first.optString("title", title).ifBlank { title }
                    text = first.optString("body", text).ifBlank { text }
                }
                // فقط اگر پیام جدیدتر از آخرین نوتیف باشد
                if (newestId > 0 && newestId <= lastNotifiedId) return@execute
                if (newestId > 0) {
                    sp.edit().putInt("last_notif_id", newestId).apply()
                }
                main.post { showNotification(activity, title, text, base) }
            } catch (_: Exception) {
            }
        }
    }

    private fun showNotification(ctx: Context, title: String, text: String, serverBase: String) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_URL, "${serverBase.trimEnd('/')}/messages")
        }
        val pi = PendingIntent.getActivity(
            ctx, 1001, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(ctx).notify(9001 + (System.currentTimeMillis() % 100).toInt(), notif)
            }
        } catch (_: Exception) {
        }
    }
}

object CookieHeader {
    fun from(ctx: Context): String {
        val prefs = Prefs(ctx)
        if (prefs.savedCookies.isNotBlank()) return prefs.savedCookies
        return collectFromManager(prefs.serverUrl)
    }

    fun refreshFromWebView(ctx: Context) {
        try {
            val prefs = Prefs(ctx)
            val collected = collectFromManager(prefs.serverUrl)
            if (collected.isNotBlank()) {
                // merge
                val set = linkedSetOf<String>()
                prefs.savedCookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { set.add(it) }
                collected.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { set.add(it) }
                if (set.isNotEmpty()) prefs.savedCookies = set.joinToString("; ")
            }
        } catch (_: Exception) {
        }
    }

    private fun collectFromManager(serverBase: String): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val base = serverBase.trimEnd('/')
            val host = try {
                val u = android.net.Uri.parse(if (base.contains("://")) base else "http://$base")
                "${u.scheme}://${u.host}"
            } catch (_: Exception) { base }
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

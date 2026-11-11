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
            val ch = NotificationChannel(
                CHANNEL_ID,
                "پیام‌های صندوق",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "اعلان پیام‌های جدید صندوق خانواده" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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

    /** بررسی پس‌زمینه حتی وقتی اپ بسته است (حداقل هر ۱۵ دقیقه — محدودیت اندروید) */
    fun scheduleBackground(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            MessageCheckWorker.UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    fun startPolling(activity: MainActivity, serverBase: String) {
        ensureChannel(activity)
        requestPermissionIfNeeded(activity)
        scheduleBackground(activity.applicationContext)
        // heartbeat حضور
        io.execute {
            try {
                val base = serverBase.trimEnd('/')
                val cookie = CookieHeader.from(activity)
                val conn = (URL("$base/api/heartbeat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    setRequestProperty("Cookie", cookie)
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
        if (scheduled) return
        scheduled = true
        val runnable = object : Runnable {
            override fun run() {
                poll(activity, serverBase)
                main.postDelayed(this, 45_000L)
            }
        }
        main.postDelayed(runnable, 8_000L)
    }

    private fun poll(activity: MainActivity, serverBase: String) {
        io.execute {
            try {
                val base = serverBase.trimEnd('/')
                val conn = (URL("$base/api/messages/unread").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    val cookie = CookieHeader.from(activity)
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect(); return@execute
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val count = json.optInt("count", 0)
                if (count <= 0) return@execute
                val arr = json.optJSONArray("messages") ?: return@execute
                val prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                val seen = prefs.getStringSet("seen_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
                var newestId = 0
                var title = "پیام جدید صندوق"
                var text = "$count پیام خوانده‌نشده"
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    newestId = first.optInt("id", 0)
                    title = first.optString("title", title)
                    text = first.optString("body", text)
                }
                if (newestId > 0 && seen.contains(newestId.toString())) return@execute
                if (newestId > 0) {
                    seen.add(newestId.toString())
                    prefs.edit().putStringSet("seen_ids", seen.toList().takeLast(50).toSet()).apply()
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(ctx).notify(9001, notif)
            }
        } catch (_: Exception) {
        }
    }
}

object CookieHeader {
    fun from(ctx: Context): String {
        val prefs = Prefs(ctx)
        if (prefs.savedCookies.isNotBlank()) return prefs.savedCookies
        return try {
            val base = prefs.serverUrl
            android.webkit.CookieManager.getInstance().getCookie(base) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}

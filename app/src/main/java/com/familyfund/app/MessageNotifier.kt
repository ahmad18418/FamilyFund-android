package com.familyfund.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object MessageNotifier {
    private const val CHANNEL = MessageCheckWorker.CHANNEL
    private const val ALARM_REQ = 7101
    private const val INTERVAL_MS = 5 * 60 * 1000L // 5 دقیقه

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var scheduled = false

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "پیام‌های صندوق",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پیام حتی وقتی برنامه بسته است"
                enableVibration(true)
                setShowBadge(true)
            }
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4401
                )
            }
        }
        ensureChannel(activity)
        // درخواست خروج از بهینه‌سازی باتری — مهم برای نوتیف پس‌زمینه
        tryRequestIgnoreBattery(activity)
    }

    fun tryRequestIgnoreBattery(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = activity.packageName
            if (pm.isIgnoringBatteryOptimizations(pkg)) return
            // فقط یک‌بار در هر نصب پیشنهاد بده
            val sp = activity.getSharedPreferences("ff_msg_notify", Context.MODE_PRIVATE)
            if (sp.getBoolean("battery_asked", false)) return
            sp.edit().putBoolean("battery_asked", true).apply()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun scheduleImmediate(ctx: Context) {
        try {
            ensureChannel(ctx)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<MessageCheckWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        } catch (_: Exception) {
        }
    }

    fun scheduleBackground(ctx: Context) {
        try {
            ensureChannel(ctx)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // حداقل دوره WorkManager: ۱۵ دقیقه
            val req = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                MessageCheckWorker.UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        } catch (_: Exception) {
        }
        scheduleAlarm(ctx)
    }

    /** AlarmManager قابل‌اعتمادتر از WorkManager برای چک دوره‌ای کوتاه */
    fun scheduleAlarm(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (_: Exception) {
        }
    }

    fun cancelAlarm(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    /** وقتی اپ باز است هر ۴۵ ثانیه هم چک می‌کند */
    fun startPolling(activity: Activity, serverUrl: String) {
        requestPermission(activity)
        scheduleBackground(activity.applicationContext)
        scheduleImmediate(activity.applicationContext)
        stopPolling()
        if (scheduled) return
        scheduled = true
        val base = serverUrl.trimEnd('/')
        pollRunnable = object : Runnable {
            override fun run() {
                Thread {
                    try {
                        pollOnce(activity.applicationContext, base)
                    } catch (_: Exception) {
                    }
                    mainHandler.postDelayed(this, 45_000L)
                }.start()
            }
        }
        mainHandler.postDelayed(pollRunnable!!, 8_000L)
    }

    fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
        scheduled = false
    }

    private fun pollOnce(ctx: Context, base: String) {
        val prefs = Prefs(ctx)
        var cookie = prefs.savedCookies
        if (cookie.isBlank()) {
            CookieHeader.refreshFromWebView(ctx)
            cookie = CookieHeader.from(ctx)
        }
        if (cookie.isBlank()) return
        val conn = (URL("$base/api/messages/unread").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val json = JSONObject(body)
        val count = json.optInt("count", 0)
        if (count <= 0) return
        val arr = json.optJSONArray("messages")
        var title = "پیام جدید"
        var text = "$count پیام خوانده‌نشده"
        var newestId = 0
        if (arr != null && arr.length() > 0) {
            val first = arr.getJSONObject(0)
            newestId = first.optInt("id", 0)
            if (first.optString("title").isNotBlank()) title = first.optString("title")
            if (first.optString("body").isNotBlank()) text = first.optString("body")
        }
        val sp = ctx.getSharedPreferences("ff_msg_notify", Context.MODE_PRIVATE)
        val last = sp.getInt("last_notif_id", 0)
        if (newestId > 0 && newestId <= last) return
        if (newestId > 0) sp.edit().putInt("last_notif_id", newestId).apply()
        showLocal(ctx, base, title, text, count)
    }

    private fun showLocal(ctx: Context, base: String, title: String, text: String, count: Int) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_URL, "$base/messages")
        }
        val pi = PendingIntent.getActivity(
            ctx, 2003, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setNumber(count)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(ctx).notify(9002, notif)
            }
        } catch (_: Exception) {
        }
    }
}

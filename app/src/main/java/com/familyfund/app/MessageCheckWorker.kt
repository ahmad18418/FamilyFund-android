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

class MessageCheckWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            val prefs = Prefs(applicationContext)
            CookieHeader.refreshFromWebView(applicationContext)
            val base = prefs.serverUrl.trimEnd('/')
            val cookie = CookieHeader.from(applicationContext)
            if (cookie.isBlank()) return Result.success()

            val conn = (URL("$base/api/messages/unread").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FamilyFundApp-Worker")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return Result.retry()
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val count = json.optInt("count", 0)
            if (count <= 0) return Result.success()

            val arr = json.optJSONArray("messages")
            var title = "پیام جدید صندوق"
            var text = "$count پیام خوانده‌نشده"
            var newestId = 0
            if (arr != null && arr.length() > 0) {
                val first = arr.getJSONObject(0)
                newestId = first.optInt("id", 0)
                title = first.optString("title", title)
                text = first.optString("body", text)
            }

            val sp = applicationContext.getSharedPreferences("ff_msg_notify", Context.MODE_PRIVATE)
            val last = sp.getInt("last_notif_id", 0)
            if (newestId > 0 && newestId <= last) return Result.success()
            if (newestId > 0) sp.edit().putInt("last_notif_id", newestId).apply()

            ensureChannel()
            val open = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(9002, notif)
            } catch (_: SecurityException) {
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "پیام‌های صندوق",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL = "family_fund_messages"
        const val UNIQUE = "family_fund_msg_check"
    }
}

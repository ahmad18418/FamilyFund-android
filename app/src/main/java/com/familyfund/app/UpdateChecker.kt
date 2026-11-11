package com.familyfund.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class RemoteInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val force: Boolean,
        val apkUrl: String
    )

    fun check(activity: Activity, serverBase: String, manual: Boolean = false) {
        io.execute {
            try {
                val base = serverBase.trimEnd('/')
                val info = fetchRemote(base)
                if (info == null) {
                    if (manual) {
                        main.post {
                            Toast.makeText(activity, "ارتباط با سرور برای بررسی نسخه برقرار نشد", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@execute
                }
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    main.post {
                        if (!activity.isFinishing) {
                            showDialog(activity, info)
                        }
                    }
                } else if (manual) {
                    main.post {
                        Toast.makeText(
                            activity,
                            "نسخه شما به‌روز است (${BuildConfig.VERSION_NAME})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) {
                    main.post {
                        Toast.makeText(activity, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun fetchRemote(serverBase: String): RemoteInfo? {
        val base = serverBase.trimEnd('/')
        val body = httpGet("$base/api/app-version") ?: return null
        val json = JSONObject(body)
        val remoteCode = json.optInt("versionCode", 0)
        if (remoteCode <= 0) return null
        var apkUrl = json.optString("apkUrl", "")
        if (apkUrl.isBlank() || !apkUrl.startsWith("http")) {
            apkUrl = "$base/download/android"
        }
        return RemoteInfo(
            versionCode = remoteCode,
            versionName = json.optString("versionName", remoteCode.toString()),
            notes = json.optString("notes", ""),
            force = json.optBoolean("force", false),
            apkUrl = apkUrl
        )
    }

    fun downloadAndInstall(activity: Activity, apkUrl: String) {
        Toast.makeText(activity, "در حال دانلود نسخه جدید…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val outFile = File(activity.cacheDir, "family-fund-update.apk")
                if (outFile.exists()) outFile.delete()
                downloadToFile(apkUrl, outFile)
                if (!outFile.exists() || outFile.length() < 10_000) {
                    throw IllegalStateException("فایل دانلود ناقص است")
                }
                main.post { installApk(activity, outFile) }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(activity, "خطا در دانلود: ${e.message}", Toast.LENGTH_LONG).show()
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun showDialog(activity: Activity, info: RemoteInfo) {
        val msg = buildString {
            append("نسخه جدید ${info.versionName} (${info.versionCode}) آماده است.\n")
            append("نسخه فعلی: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            if (info.notes.isNotBlank()) append("\n${info.notes}")
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("بروزرسانی اپ")
            .setMessage(msg)
            .setCancelable(!info.force)
            .setPositiveButton("دانلود و نصب") { _, _ ->
                downloadAndInstall(activity, info.apkUrl)
            }
            .setNeutralButton("لینک دانلود") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        if (!info.force) b.setNegativeButton("بعداً", null)
        b.show()
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "FamilyFundApp/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToFile(urlStr: String, outFile: File) {
        var current = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 180000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FamilyFundApp/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: break
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            return
        }
        throw IllegalStateException("redirect loop")
    }

    fun installApk(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "اجازه نصب از منابع ناشناخته را بدهید و دوباره تلاش کنید",
                    Toast.LENGTH_LONG
                ).show()
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${activity.packageName}"))
                )
                return
            }
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}

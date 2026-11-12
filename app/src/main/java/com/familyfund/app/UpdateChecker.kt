package com.familyfund.app

import android.app.Activity
import android.app.ProgressDialog
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

    fun fetchRemote(base: String): RemoteInfo? {
        val urls = listOf(
            "$base/api/app-version",
            "$base/api/version"
        )
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val j = JSONObject(body)
                val code = j.optInt("versionCode", j.optInt("version_code", 0))
                val name = j.optString("versionName", j.optString("version_name", ""))
                val notes = j.optString("notes", j.optString("changelog", ""))
                val force = j.optBoolean("force", false)
                var apk = j.optString("apkUrl", j.optString("apk_url", ""))
                if (apk.isBlank()) apk = "$base/static/apk/familyfund.apk"
                if (!apk.startsWith("http")) {
                    apk = base.trimEnd('/') + (if (apk.startsWith("/")) apk else "/$apk")
                }
                if (code > 0) {
                    return RemoteInfo(code, name, notes, force, apk)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun showDialog(activity: Activity, info: RemoteInfo) {
        val msg = buildString {
            append("نسخه جدید ")
            append(info.versionName.ifBlank { info.versionCode.toString() })
            append(" آماده است.\n")
            if (info.notes.isNotBlank()) {
                append("\n")
                append(info.notes)
            }
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("بروزرسانی برنامه")
            .setMessage(msg)
            .setCancelable(!info.force)
            .setPositiveButton("دانلود و نصب") { _, _ ->
                downloadAndInstall(activity, info.apkUrl)
            }
        if (!info.force) {
            b.setNegativeButton("بعداً", null)
        }
        b.show()
    }

    fun downloadAndInstall(activity: Activity, apkUrl: String) {
        if (apkUrl.isBlank()) {
            Toast.makeText(activity, "آدرس فایل یافت نشد", Toast.LENGTH_SHORT).show()
            return
        }
        // Progress dialog
        @Suppress("DEPRECATION")
        val dlg = ProgressDialog(activity).apply {
            setTitle("دانلود نسخه جدید")
            setMessage("در حال آماده‌سازی…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        Toast.makeText(activity, "دانلود شروع شد…", Toast.LENGTH_SHORT).show()

        io.execute {
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 120000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    main.post {
                        dlg.dismiss()
                        Toast.makeText(activity, "خطا در دانلود ($code)", Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }
                val total = conn.contentLengthLong.coerceAtLeast(0)
                val dir = File(activity.cacheDir, "apk").apply { mkdirs() }
                val outFile = File(dir, "familyfund-update.apk")
                if (outFile.exists()) outFile.delete()
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var n: Int
                        var lastPct = -1
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            readTotal += n
                            val pct = if (total > 0) ((readTotal * 100) / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                val rt = readTotal
                                main.post {
                                    if (pct >= 0) {
                                        dlg.isIndeterminate = false
                                        dlg.progress = pct
                                        dlg.setMessage("در حال دانلود… $pct٪")
                                    } else {
                                        dlg.isIndeterminate = true
                                        val mb = rt / (1024.0 * 1024.0)
                                        dlg.setMessage(String.format("در حال دانلود… %.1f مگابایت", mb))
                                    }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                main.post {
                    dlg.setMessage("دانلود تمام شد. در حال باز کردن نصب‌کننده…")
                    dlg.progress = 100
                }
                Thread.sleep(400)
                main.post {
                    dlg.dismiss()
                    installApk(activity, outFile)
                }
            } catch (e: Exception) {
                main.post {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    Toast.makeText(activity, "خطای دانلود: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        activity,
                        "اجازه نصب از منابع ناشناخته را بدهید",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
            }
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "خطا در نصب: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

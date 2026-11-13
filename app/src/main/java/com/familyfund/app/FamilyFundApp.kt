package com.familyfund.app

import android.app.Application

class FamilyFundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MessageNotifier.ensureChannel(this)
            // اگر کاربر قبلاً وارد شده و کوکی/رمز دارد، پس‌زمینه را فعال کن
            val prefs = Prefs(this)
            if (prefs.savedCookies.isNotBlank() || prefs.hasCredentials()) {
                MessageNotifier.scheduleBackground(this)
            }
        } catch (_: Exception) {
        }
    }
}

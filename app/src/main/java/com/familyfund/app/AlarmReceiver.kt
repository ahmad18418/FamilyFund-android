package com.familyfund.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** هر چند دقیقه یک‌بار توسط AlarmManager صدا زده می‌شود */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // اجرای فوری Worker در پس‌زمینه
        MessageNotifier.scheduleImmediate(app)
        // زمان‌بندی زنگ بعدی
        MessageNotifier.scheduleAlarm(app)
    }
}

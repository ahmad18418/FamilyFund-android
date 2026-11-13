package com.familyfund.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** بعد از روشن شدن گوشی، زمان‌بندی نوتیف پس‌زمینه را دوباره فعال می‌کند */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_REPLACED
        ) {
            MessageNotifier.scheduleBackground(context.applicationContext)
            MessageNotifier.scheduleImmediate(context.applicationContext)
        }
    }
}

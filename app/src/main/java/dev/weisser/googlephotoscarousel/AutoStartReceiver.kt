package dev.weisser.googlephotoscarousel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the slideshow app after a TV reboot when the user has left auto-start enabled. */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("carousel", Context.MODE_PRIVATE)
            .getBoolean("auto_start_on_boot", true)
        if (!enabled) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(launch) }
    }
}

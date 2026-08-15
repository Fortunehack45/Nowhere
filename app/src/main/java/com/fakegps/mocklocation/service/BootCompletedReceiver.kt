package com.fakegps.mocklocation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.fakegps.mocklocation.data.preferences.SessionPreferences

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(TAG, "Boot completed event received ($action). Checking previous session state...")
            val sessionPrefs = SessionPreferences(context)

            if (sessionPrefs.isSessionActive) {
                Log.d(TAG, "Restoring active mock location session for mode: ${sessionPrefs.activeMode}")
                val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                    this.action = MockLocationService.ACTION_RESTORE_SESSION
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "No active session before reboot; taking no action.")
            }
        }
    }
}

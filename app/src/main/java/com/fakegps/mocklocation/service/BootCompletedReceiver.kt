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
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON" || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d(TAG, "Boot event received ($action). Checking session persistence preferences...")
            val sessionPrefs = SessionPreferences(context)

            // Strictly require that the user explicitly enabled persistent boot injection in settings
            if (sessionPrefs.isPersistentBootInjectionEnabled && sessionPrefs.isSessionActive) {
                Log.d(TAG, "User explicitly configured persistent boot mock location. Restoring mode: ${sessionPrefs.activeMode}")
                val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                    this.action = MockLocationService.ACTION_RESTORE_SESSION
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start service on boot: ${e.message}")
                }
            } else {
                Log.d(TAG, "Persistent boot injection is disabled or no active session; taking no action.")
            }
        }
    }
}

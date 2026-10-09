package com.fakegps.mocklocation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class MockLocationServiceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_JOYSTICK_VECTOR = "com.fakegps.mocklocation.ACTION_JOYSTICK_VECTOR"
        const val EXTRA_ANGLE = "extra_angle"
        const val EXTRA_MAGNITUDE = "extra_magnitude"
        const val EXTRA_SPEED = "extra_speed"

        const val ACTION_RESTORE_MOCK_SESSION = "com.fakegps.mocklocation.ACTION_RESTORE_MOCK_SESSION"

        var activeService: MockLocationService? = null

        fun sendJoystickUpdate(context: Context, angle: Float, magnitude: Float, speedKmh: Float? = null) {
            if (activeService != null) {
                activeService?.updateJoystickVector(angle, magnitude, speedKmh)
            } else if (magnitude > 0.05f) {
                val intent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START_JOYSTICK
                    if (speedKmh != null) {
                        putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        when (intent?.action) {
            ACTION_JOYSTICK_VECTOR -> {
                val angle = intent.getFloatExtra(EXTRA_ANGLE, 0.0f)
                val magnitude = intent.getFloatExtra(EXTRA_MAGNITUDE, 0.0f)
                val speed = if (intent.hasExtra(EXTRA_SPEED)) intent.getFloatExtra(EXTRA_SPEED, 20.0f) else null
                sendJoystickUpdate(context, angle, magnitude, speed)
            }
            ACTION_RESTORE_MOCK_SESSION -> {
                android.util.Log.i("MockLocationReceiver", "ACTION_RESTORE_MOCK_SESSION received. Checking simulation status...")
                val sessionPrefs = com.fakegps.mocklocation.data.preferences.SessionPreferences(context)
                if (sessionPrefs.isSessionActive && sessionPrefs.isPersistentBootInjectionEnabled) {
                    if (MockLocationService.isSimulationRunning()) {
                        // Re-arm watchdog check for continuous background health
                        activeService?.scheduleWatchdog()
                    } else {
                        // Simulation is NOT running. Do NOT auto-connect or start foreground service out of nowhere!
                        android.util.Log.d("MockLocationReceiver", "Simulation is not active; suppressing auto-connect from background alarm.")
                    }
                } else {
                    android.util.Log.d("MockLocationReceiver", "Session not active or persistent mode disabled. Ignoring restore intent.")
                }
            }
        }
    }
}

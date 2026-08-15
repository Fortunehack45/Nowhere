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
        if (intent?.action == ACTION_JOYSTICK_VECTOR) {
            val angle = intent.getFloatExtra(EXTRA_ANGLE, 0.0f)
            val magnitude = intent.getFloatExtra(EXTRA_MAGNITUDE, 0.0f)
            val speed = if (intent.hasExtra(EXTRA_SPEED)) intent.getFloatExtra(EXTRA_SPEED, 20.0f) else null
            if (context != null) {
                sendJoystickUpdate(context, angle, magnitude, speed)
            }
        }
    }
}

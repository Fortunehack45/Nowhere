package com.fakegps.mocklocation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MockLocationServiceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_JOYSTICK_VECTOR = "com.fakegps.mocklocation.ACTION_JOYSTICK_VECTOR"
        const val EXTRA_ANGLE = "extra_angle"
        const val EXTRA_MAGNITUDE = "extra_magnitude"

        var activeService: MockLocationService? = null

        fun sendJoystickUpdate(context: Context, angle: Float, magnitude: Float) {
            activeService?.updateJoystickVector(angle, magnitude)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_JOYSTICK_VECTOR) {
            val angle = intent.getFloatExtra(EXTRA_ANGLE, 0.0f)
            val magnitude = intent.getFloatExtra(EXTRA_MAGNITUDE, 0.0f)
            activeService?.updateJoystickVector(angle, magnitude)
        }
    }
}

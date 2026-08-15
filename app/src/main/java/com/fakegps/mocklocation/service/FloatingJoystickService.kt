package com.fakegps.mocklocation.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import androidx.appcompat.view.ContextThemeWrapper
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.custom.JoystickView

class FloatingJoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var sessionPrefs: SessionPreferences

    companion object {
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingJoystickService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingJoystickService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sessionPrefs = SessionPreferences(this)
        isRunning = true
        showFloatingJoystick()
    }

    private fun showFloatingJoystick() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 80
                y = 350
            }

            // Wrap in Application Theme to safely resolve theme attributes and drawables
            val themedContext = ContextThemeWrapper(this, R.style.Theme_MockLocation)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.layout_floating_joystick, null)
            floatingView = view

            val joystick = view.findViewById<JoystickView>(R.id.floatingJoystick)
            val btnClose = view.findViewById<ImageButton>(R.id.btnFloatingClose)
            val dragHandle = view.findViewById<View>(R.id.floatingDragHandle)

            joystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
                override fun onJoystickMoved(angleDegrees: Float, magnitude: Float) {
                    if (MockLocationServiceReceiver.activeService == null) {
                        // Automatically spin up joystick spoofing if not already running
                        val intent = Intent(this@FloatingJoystickService, MockLocationService::class.java).apply {
                            action = MockLocationService.ACTION_START_JOYSTICK
                            putExtra(MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                            putExtra(MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                            putExtra(MockLocationService.EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                    MockLocationServiceReceiver.sendJoystickUpdate(this@FloatingJoystickService, angleDegrees, magnitude)
                }
            })

            btnClose.setOnClickListener {
                stopSelf()
            }

            // Dragging Handler
            dragHandle.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event == null) return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager?.updateViewLayout(floatingView, params)
                            } catch (ignored: Exception) {}
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (ignored: Exception) {}
        }
        floatingView = null
        super.onDestroy()
    }
}

package com.fakegps.mocklocation.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.ui.custom.JoystickView

class FloatingJoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

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
        isRunning = true
        showFloatingJoystick()
    }

    private fun showFloatingJoystick() {
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
            x = 60
            y = 300
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_floating_joystick, null)
        floatingView = view

        val joystick = view.findViewById<JoystickView>(R.id.floatingJoystick)
        val btnClose = view.findViewById<ImageButton>(R.id.btnFloatingClose)
        val dragHandle = view.findViewById<View>(R.id.floatingDragHandle)

        joystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onJoystickMoved(angleDegrees: Float, magnitude: Float) {
                MockLocationServiceReceiver.sendJoystickUpdate(this@FloatingJoystickService, angleDegrees, magnitude)
            }
        })

        btnClose.setOnClickListener {
            stopSelf()
        }

        // Dragging handler
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
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
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

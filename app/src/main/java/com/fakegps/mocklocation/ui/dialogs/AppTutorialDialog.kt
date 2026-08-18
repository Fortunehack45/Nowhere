package com.fakegps.mocklocation.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutDialogAppTutorialBinding

data class TutorialStep(
    val stepNumber: Int,
    val totalSteps: Int,
    val title: String,
    val locationBadge: String,
    val description: String,
    val iconResId: Int
)

class AppTutorialDialog(
    context: Context,
    private val onTutorialCompleted: (() -> Unit)? = null
) : Dialog(context) {

    private lateinit var binding: LayoutDialogAppTutorialBinding
    private var currentStepIndex = 0

    private val steps = listOf(
        TutorialStep(
            stepNumber = 1,
            totalSteps = 6,
            title = "Global Address & Coordinates Search",
            locationBadge = "📍 Top Search Bar",
            description = "Search any city, landmark, street address, or exact GPS coordinates (e.g. 48.8584, 2.2945) to instantly center the map and place your target mock pin.",
            iconResId = R.drawable.ic_search
        ),
        TutorialStep(
            stepNumber = 2,
            totalSteps = 6,
            title = "Simulation Modes",
            locationBadge = "🔀 Bottom Mode Switcher Tabs",
            description = "Toggle between 3 simulation modes:\n• Fixed Teleport: Hold fixed location anywhere.\n• Route Simulation: Multi-point path with customizable speed & looping.\n• Joystick: Real-time interactive 360° navigation.",
            iconResId = R.drawable.ic_teleport
        ),
        TutorialStep(
            stepNumber = 3,
            totalSteps = 6,
            title = "Collapsible Floating Tools Stack",
            locationBadge = "🛠️ Right-Hand Floating Drawer",
            description = "Tap the right drawer button to access:\n• Map Layers: Vector, Satellite & Topo.\n• Altitude & Realistic Signal Jitter.\n• History & Favorites bookmarking.\n• Live Weather Radar powered by Nowhere.",
            iconResId = R.drawable.ic_layers
        ),
        TutorialStep(
            stepNumber = 4,
            totalSteps = 6,
            title = "Autonomous Privacy VPN & Shield",
            locationBadge = "🛡️ Built-in Loopback Tunnel",
            description = "Automatically connects to the nearest country node as soon as mock location starts. Keeps background mock GPS running 24/7 across aggressive phone battery savers.",
            iconResId = R.drawable.ic_shield_check
        ),
        TutorialStep(
            stepNumber = 5,
            totalSteps = 6,
            title = "Lower-Left Radar Joystick",
            locationBadge = "🕹️ Bottom-Left Screen Corner",
            description = "A compact, fluid radar stick positioned on the bottom-left so it never blocks or overlaps the right-side map tools. Steer dynamically at up to 120 km/h.",
            iconResId = R.drawable.ic_joystick
        ),
        TutorialStep(
            stepNumber = 6,
            totalSteps = 6,
            title = "Master Start / Stop Control",
            locationBadge = "🚀 Main Bottom Action Button",
            description = "Tap Start to immediately spoof your GPS coordinates system-wide across all apps and games. Tap Stop anytime to release mock location.",
            iconResId = R.drawable.ic_play
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = LayoutDialogAppTutorialBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setCancelable(true)

        setupListeners()
        renderStep(0)
    }

    private fun setupListeners() {
        binding.btnTutorialClose.setOnClickListener {
            dismiss()
            onTutorialCompleted?.invoke()
        }

        binding.btnTutorialPrev.setOnClickListener {
            if (currentStepIndex > 0) {
                currentStepIndex--
                renderStep(currentStepIndex)
            }
        }

        binding.btnTutorialNext.setOnClickListener {
            if (currentStepIndex < steps.size - 1) {
                currentStepIndex++
                renderStep(currentStepIndex)
            } else {
                dismiss()
                onTutorialCompleted?.invoke()
            }
        }
    }

    private fun renderStep(index: Int) {
        val step = steps[index]

        binding.tvTutorialStepBadge.text = "STEP ${step.stepNumber} OF ${step.totalSteps}"
        binding.tvTutorialTitle.text = step.title
        binding.tvTutorialLocationBadge.text = step.locationBadge
        binding.tvTutorialDescription.text = step.description
        binding.ivTutorialIcon.setImageResource(step.iconResId)

        binding.btnTutorialPrev.visibility = if (index == 0) View.GONE else View.VISIBLE
        binding.btnTutorialNext.text = if (index == steps.size - 1) "Finish Tour" else "Next Step"

        setupDots(index)
    }

    private fun setupDots(currentIndex: Int) {
        binding.layoutTutorialDots.removeAllViews()

        for (i in steps.indices) {
            val dot = ImageView(context).apply {
                if (i == currentIndex) {
                    layoutParams = LinearLayout.LayoutParams(36, 12).apply {
                        setMargins(6, 0, 6, 0)
                    }
                    setImageResource(R.drawable.bg_status_pill)
                    setColorFilter(ContextCompat.getColor(context, R.color.primary))
                } else {
                    layoutParams = LinearLayout.LayoutParams(12, 12).apply {
                        setMargins(6, 0, 6, 0)
                    }
                    setImageResource(R.drawable.bg_status_pill)
                    setColorFilter(ContextCompat.getColor(context, R.color.stroke_subtle))
                }
            }
            binding.layoutTutorialDots.addView(dot)
        }
    }
}

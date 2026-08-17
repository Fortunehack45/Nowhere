package com.fakegps.mocklocation.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.databinding.ActivityFeatureWalkthroughBinding
import com.fakegps.mocklocation.databinding.ItemWalkthroughSlideBinding

data class WalkthroughSlide(
    val badgeText: String,
    val title: String,
    val description: String,
    val iconResId: Int
)

class FeatureWalkthroughActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeatureWalkthroughBinding
    private lateinit var settingsPrefs: AppSettingsPreferences

    private val slides = listOf(
        WalkthroughSlide(
            badgeText = "MODE 01 • TELEPORT",
            title = "Instant Precision Teleport",
            description = "Instantly spoof your device GPS coordinates anywhere on Earth with customizable altitude, accuracy, and realistic satellite jitter.",
            iconResId = R.drawable.ic_teleport
        ),
        WalkthroughSlide(
            badgeText = "MODE 02 • ROUTE SIMULATION",
            title = "Dynamic Multi-Point Routing",
            description = "Plot realistic paths across roads, marine waters, or flight corridors with real-time speed controls, auto-looping, and live telemetry.",
            iconResId = R.drawable.ic_route
        ),
        WalkthroughSlide(
            badgeText = "MODE 03 • RADAR JOYSTICK",
            title = "360° Floating Radar Joystick",
            description = "Steer and navigate your mock position interactively in real time with an overlay joystick that floats directly over your favorite apps.",
            iconResId = R.drawable.ic_joystick
        ),
        WalkthroughSlide(
            badgeText = "MODE 04 • PRIVACY & TOOLS",
            title = "IP Shield & Location Weather",
            description = "Mask your virtual network identity and explore real-time weather radars, live forecasts, and convenient home screen widgets.",
            iconResId = R.drawable.ic_shield
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)

        // If user already completed the feature walkthrough, forward immediately
        if (settingsPrefs.hasCompletedFeatureWalkthrough) {
            proceedToNextScreen()
            return
        }

        binding = ActivityFeatureWalkthroughBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCarousel()
        setupListeners()
    }

    private fun setupCarousel() {
        binding.vpWalkthrough.adapter = WalkthroughAdapter(slides)
        setupDotIndicators(0)

        binding.vpWalkthrough.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupDotIndicators(position)
                if (position == slides.size - 1) {
                    binding.btnWalkthroughNext.text = "Get Started"
                    binding.btnWalkthroughSkip.visibility = View.INVISIBLE
                } else {
                    binding.btnWalkthroughNext.text = "Next"
                    binding.btnWalkthroughSkip.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun setupDotIndicators(currentPosition: Int) {
        binding.layoutDotsIndicator.removeAllViews()
        val dotParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(8, 0, 8, 0)
        }

        for (i in slides.indices) {
            val dot = ImageView(this).apply {
                if (i == currentPosition) {
                    layoutParams = LinearLayout.LayoutParams(40, 14).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setImageResource(R.drawable.bg_status_pill)
                    setColorFilter(ContextCompat.getColor(this@FeatureWalkthroughActivity, R.color.primary))
                } else {
                    layoutParams = LinearLayout.LayoutParams(14, 14).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setImageResource(R.drawable.bg_status_pill)
                    setColorFilter(ContextCompat.getColor(this@FeatureWalkthroughActivity, R.color.stroke_subtle))
                }
            }
            binding.layoutDotsIndicator.addView(dot)
        }
    }

    private fun setupListeners() {
        binding.btnWalkthroughSkip.setOnClickListener {
            completeWalkthrough()
        }

        binding.btnWalkthroughNext.setOnClickListener {
            val current = binding.vpWalkthrough.currentItem
            if (current < slides.size - 1) {
                binding.vpWalkthrough.currentItem = current + 1
            } else {
                completeWalkthrough()
            }
        }
    }

    private fun completeWalkthrough() {
        settingsPrefs.hasCompletedFeatureWalkthrough = true
        proceedToNextScreen()
    }

    private fun proceedToNextScreen() {
        val intent = Intent(this, WelcomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    private class WalkthroughAdapter(
        private val slides: List<WalkthroughSlide>
    ) : RecyclerView.Adapter<WalkthroughAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemWalkthroughSlideBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(slides[position])
        }

        override fun getItemCount(): Int = slides.size

        inner class ViewHolder(private val binding: ItemWalkthroughSlideBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(slide: WalkthroughSlide) {
                binding.tvSlideBadge.text = slide.badgeText
                binding.tvSlideHeadline.text = slide.title
                binding.tvSlideDescription.text = slide.description
                binding.ivSlideGraphic.setImageResource(slide.iconResId)
            }
        }
    }
}

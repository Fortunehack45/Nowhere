package com.fakegps.mocklocation.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.databinding.ActivityWelcomeBinding
import com.fakegps.mocklocation.databinding.ItemWalkthroughSlideBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper
import com.fakegps.mocklocation.util.ThemeColorManager

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var walkthroughAdapter: WalkthroughPagerAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        walkthroughAdapter.notifyItemChanged(3)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)

        // Only show onboarding the very first time!
        if (settingsPrefs.hasCompletedOnboarding) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge system bar insets (Android 15+ & targetSdk 35)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutWalkthroughHeader.setPadding(
                binding.layoutWalkthroughHeader.paddingLeft,
                statusBarInset.top + (12 * resources.displayMetrics.density).toInt(),
                binding.layoutWalkthroughHeader.paddingRight,
                binding.layoutWalkthroughHeader.paddingBottom
            )

            binding.layoutWalkthroughBottomBar.setPadding(
                binding.layoutWalkthroughBottomBar.paddingLeft,
                binding.layoutWalkthroughBottomBar.paddingTop,
                binding.layoutWalkthroughBottomBar.paddingRight,
                navBarInset.bottom + (16 * resources.displayMetrics.density).toInt()
            )

            insets
        }

        setupViewPager()
        setupListeners()
        requestEssentialPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (::walkthroughAdapter.isInitialized) {
            walkthroughAdapter.notifyItemChanged(3)
        }
    }

    private fun setupViewPager() {
        val slides = listOf(
            WalkthroughSlide(
                iconRes = R.drawable.ic_teleport,
                badgeText = "STEP 01 • TELEPORT",
                headlineText = "Track & Teleport Anywhere",
                descriptionText = "Instantly spoof your device GPS coordinates anywhere across the globe with realistic satellite jitter, altitude, and bearing precision."
            ),
            WalkthroughSlide(
                iconRes = R.drawable.ic_route,
                badgeText = "STEP 02 • SIMULATION",
                headlineText = "Realistic Routes & 360° Joystick",
                descriptionText = "Simulate authentic driving, cycling, and walking trips with real-time speed curves, cornering deceleration, and intuitive thumbstick steering."
            ),
            WalkthroughSlide(
                iconRes = R.drawable.ic_shield_check,
                badgeText = "STEP 03 • STEALTH & SYNC",
                headlineText = "Global Privacy & Hotspot Sync",
                descriptionText = "Tether simulated coordinates to external laptops and consoles via Wi-Fi Hotspot, with Ghost Cloak anti-detection and automated triggers."
            ),
            WalkthroughSlide(
                iconRes = R.drawable.ic_check_circle,
                badgeText = "STEP 04 • READINESS",
                headlineText = "Device Setup & Permissions",
                descriptionText = "Configure Nowhere as your Mock Location App in Developer Options and enable background location permissions to begin.",
                isSetupSlide = true
            )
        )

        walkthroughAdapter = WalkthroughPagerAdapter(slides)
        binding.pagerWalkthrough.adapter = walkthroughAdapter

        binding.pagerWalkthrough.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                updateNavigationButtons(position)
            }
        })
    }

    private fun updateIndicator(position: Int) {
        val dots = listOf(binding.viewDot1, binding.viewDot2, binding.viewDot3, binding.viewDot4)
        val density = resources.displayMetrics.density

        dots.forEachIndexed { index, dot ->
            val params = dot.layoutParams
            if (index == position) {
                params.width = (22 * density).toInt()
                dot.setBackgroundResource(R.drawable.bg_walkthrough_dot_active)
            } else {
                params.width = (7 * density).toInt()
                dot.setBackgroundResource(R.drawable.bg_walkthrough_dot_inactive)
            }
            dot.layoutParams = params
        }
    }

    private fun updateNavigationButtons(position: Int) {
        val primaryColor = ThemeColorManager.getPrimaryColor(this)

        if (position < 3) {
            binding.btnWalkthroughSkip.visibility = View.VISIBLE
            binding.btnWalkthroughNext.text = "NEXT"
            binding.btnWalkthroughNext.setIconResource(R.drawable.ic_chevron_right)
            binding.btnWalkthroughNext.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card_elevated)
            binding.btnWalkthroughNext.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnWalkthroughNext.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.btnWalkthroughSkip.visibility = View.INVISIBLE
            binding.btnWalkthroughNext.text = "ENTER NOWHERE"
            binding.btnWalkthroughNext.setIconResource(R.drawable.ic_teleport)
            binding.btnWalkthroughNext.backgroundTintList = ColorStateList.valueOf(primaryColor)
            binding.btnWalkthroughNext.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnWalkthroughNext.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        }
    }

    private fun setupListeners() {
        binding.btnWalkthroughSkip.setOnClickListener {
            binding.pagerWalkthrough.setCurrentItem(3, true)
        }

        binding.btnWalkthroughNext.setOnClickListener {
            val current = binding.pagerWalkthrough.currentItem
            if (current < 3) {
                binding.pagerWalkthrough.setCurrentItem(current + 1, true)
            } else {
                settingsPrefs.hasCompletedOnboarding = true
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun requestEssentialPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    data class WalkthroughSlide(
        val iconRes: Int,
        val badgeText: String,
        val headlineText: String,
        val descriptionText: String,
        val isSetupSlide: Boolean = false
    )

    inner class WalkthroughPagerAdapter(
        private val slides: List<WalkthroughSlide>
    ) : RecyclerView.Adapter<WalkthroughPagerAdapter.SlideViewHolder>() {

        inner class SlideViewHolder(val itemBinding: ItemWalkthroughSlideBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val itemBinding = ItemWalkthroughSlideBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SlideViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = slides.size

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            val slide = slides[position]
            with(holder.itemBinding) {
                ivSlideGraphic.setImageResource(slide.iconRes)
                tvSlideBadge.text = slide.badgeText
                tvSlideHeadline.text = slide.headlineText
                tvSlideDescription.text = slide.descriptionText

                if (slide.isSetupSlide) {
                    layoutSetupReadiness.visibility = View.VISIBLE
                    bindReadinessStatus(holder.itemBinding)
                } else {
                    layoutSetupReadiness.visibility = View.GONE
                }
            }
        }

        private fun bindReadinessStatus(itemBinding: ItemWalkthroughSlideBinding) {
            val context = this@WelcomeActivity
            val isMockEnabled = PermissionHelper.isMockLocationEnabled(context)
            val hasLocation = PermissionHelper.hasFineLocationPermission(context)
            val hasNotifications = PermissionHelper.hasNotificationPermission(context)
            val isBatteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(context)

            // Mock Provider Check
            if (isMockEnabled) {
                itemBinding.ivCheckMockProvider.setImageResource(R.drawable.ic_check_circle)
                itemBinding.tvMockProviderStatus.text = "Mock Location App Active in Developer Options"
                itemBinding.btnFixDeveloperSettings.text = "Configured"
            } else {
                itemBinding.ivCheckMockProvider.setImageResource(R.drawable.ic_warning_circle)
                itemBinding.tvMockProviderStatus.text = "Mock Location App Not Selected in Developer Options"
                itemBinding.btnFixDeveloperSettings.text = "Select Nowhere"
            }

            // Permissions Check
            if (hasLocation && hasNotifications) {
                itemBinding.ivCheckPermissions.setImageResource(R.drawable.ic_check_circle)
                itemBinding.tvPermissionStatus.text = "Location & Notification Permissions Granted"
            } else {
                itemBinding.ivCheckPermissions.setImageResource(R.drawable.ic_warning_circle)
                itemBinding.tvPermissionStatus.text = "Required Runtime Permissions Incomplete"
            }

            // Battery Optimization Check
            if (isBatteryExempt) {
                itemBinding.ivCheckBattery.setImageResource(R.drawable.ic_check_circle)
                itemBinding.tvBatteryStatus.text = "Unrestricted Background Running Active"
                itemBinding.btnFixBattery.text = "Active"
                itemBinding.btnFixBattery.isEnabled = false
            } else {
                itemBinding.ivCheckBattery.setImageResource(R.drawable.ic_warning_circle)
                itemBinding.tvBatteryStatus.text = "Battery Optimization may sleep background GPS"
                itemBinding.btnFixBattery.text = "Allow Unrestricted"
                itemBinding.btnFixBattery.isEnabled = true
            }

            itemBinding.btnFixDeveloperSettings.setOnClickListener {
                SetupGuideDialog(context) {
                    PermissionHelper.openDeveloperSettings(context)
                }.show()
            }

            itemBinding.btnFixBattery.setOnClickListener {
                PermissionHelper.requestIgnoreBatteryOptimizations(context)
            }
        }
    }
}

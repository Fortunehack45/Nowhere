package com.fakegps.mocklocation.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
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
        if (::walkthroughAdapter.isInitialized) {
            walkthroughAdapter.notifyItemChanged(3)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.fakegps.mocklocation.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)

        // One-way door: Only show onboarding the very first time!
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
    }

    override fun onResume() {
        super.onResume()
        if (::walkthroughAdapter.isInitialized) {
            walkthroughAdapter.notifyItemChanged(3)
        }
    }

    private fun setupViewPager() {
        val steps = listOf(
            SetupStep(
                type = StepType.PRESENTATION_TELEPORT,
                iconRes = R.drawable.ic_radar_target,
                badgeText = "PRECISION GPS • SUB-METER LOCK",
                headlineText = "Teleport Anywhere on Earth",
                descriptionText = "Simulate real-time GPS coordinates with sub-meter accuracy, custom altitude, and realistic satellite terrain.",
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_location_pin,
                        title = "Sub-Meter Coordinate Precision",
                        desc = "Accurate coordinate locking down to 6 decimals (~0.1m resolution)."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_globe,
                        title = "Worldwide Multi-Source Maps",
                        desc = "Explore with Mapnik, OpenTopo, Wikimedia, and USGS Satellite."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_search,
                        title = "Instant Search & Bookmarks",
                        desc = "Debounced geocoding search, local history, and bookmark drawers."
                    )
                )
            ),
            SetupStep(
                type = StepType.PRESENTATION_ROUTES,
                iconRes = R.drawable.ic_route,
                badgeText = "KINEMATICS • 360° STEERING",
                headlineText = "Dynamic Routes & Live Steering",
                descriptionText = "Plot multi-stop circuits with natural acceleration, centrifugal turn deceleration, and 360° radar joystick.",
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_route,
                        title = "Interactive Waypoint Circuits",
                        desc = "Tap anywhere on OpenStreetMap to plot route nodes with live metrics."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_joystick,
                        title = "Military-Grade Radar Joystick",
                        desc = "HUD steering with concentric distance rings, cardinal markings, and speed control."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_gpx,
                        title = "GPX Import & Route Reversal",
                        desc = "Import standard GPX tracks and 1-tap retrace your path backwards."
                    )
                )
            ),
            SetupStep(
                type = StepType.PRESENTATION_STEALTH,
                iconRes = R.drawable.ic_shield_check,
                badgeText = "STEALTH • ANTI-DETECTION",
                headlineText = "Military-Grade Anti-Detection",
                descriptionText = "Bypass mock location detection with realistic GPS jitter, Google Play Fused Provider spoofing, and Ghost Cloak.",
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_shield_check,
                        title = "Google Play Fused Provider",
                        desc = "Injects the 'fused' test provider for full compatibility with Google Play Services."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_bolt,
                        title = "Antenna Drift & Jitter Randomizer",
                        desc = "Simulates authentic satellite signal variance with configurable radius."
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_launcher_monochrome,
                        title = "Ghost Cloak Architecture",
                        desc = "Conceals mock location flags from aggressive anti-cheat systems."
                    )
                )
            ),
            SetupStep(
                type = StepType.SETUP_HUB,
                iconRes = R.drawable.ic_settings,
                badgeText = "STEP 4 OF 4 • SYSTEM READINESS",
                headlineText = "Complete Device Setup",
                descriptionText = "Configure required Android developer settings and system permissions to unlock mock GPS simulation.",
                highlights = emptyList()
            )
        )

        walkthroughAdapter = WalkthroughPagerAdapter(steps)
        binding.pagerWalkthrough.adapter = walkthroughAdapter

        // Smooth Apple HIG Parallax & Fade Page Transformer
        binding.pagerWalkthrough.setPageTransformer { page, position ->
            when {
                position < -1 -> {
                    page.alpha = 0f
                }
                position <= 1 -> {
                    val factor = 1f - Math.abs(position)
                    page.alpha = 0.35f + factor * 0.65f
                    page.scaleX = 0.94f + factor * 0.06f
                    page.scaleY = 0.94f + factor * 0.06f
                }
                else -> {
                    page.alpha = 0f
                }
            }
        }

        binding.pagerWalkthrough.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                updateNavigationButtons(position)
                binding.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        })
    }

    private fun updateIndicator(position: Int) {
        val dots = listOf(binding.viewDot1, binding.viewDot2, binding.viewDot3, binding.viewDot4)
        val density = resources.displayMetrics.density

        dots.forEachIndexed { index, dot ->
            val params = dot.layoutParams
            if (index == position) {
                params.width = (24 * density).toInt()
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
            binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.pagerWalkthrough.setCurrentItem(3, true)
        }

        binding.btnWalkthroughNext.setOnClickListener {
            binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val current = binding.pagerWalkthrough.currentItem
            if (current < 3) {
                binding.pagerWalkthrough.setCurrentItem(current + 1, true)
            } else {
                // One-way door: Once completed, lock state and replace activity
                settingsPrefs.hasCompletedOnboarding = true
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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

    enum class StepType {
        PRESENTATION_TELEPORT,
        PRESENTATION_ROUTES,
        PRESENTATION_STEALTH,
        SETUP_HUB
    }

    data class HighlightItem(
        val iconRes: Int,
        val title: String,
        val desc: String
    )

    data class SetupStep(
        val type: StepType,
        val iconRes: Int,
        val badgeText: String,
        val headlineText: String,
        val descriptionText: String,
        val highlights: List<HighlightItem>
    )

    inner class WalkthroughPagerAdapter(
        private val steps: List<SetupStep>
    ) : RecyclerView.Adapter<WalkthroughPagerAdapter.StepViewHolder>() {

        inner class StepViewHolder(val itemBinding: ItemWalkthroughSlideBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
            val itemBinding = ItemWalkthroughSlideBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return StepViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = steps.size

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            val step = steps[position]
            val context = this@WelcomeActivity

            with(holder.itemBinding) {
                ivSlideGraphic.setImageResource(step.iconRes)
                tvSlideBadge.text = step.badgeText
                tvSlideHeadline.text = step.headlineText
                tvSlideDescription.text = step.descriptionText

                if (step.type == StepType.SETUP_HUB) {
                    cardFeatureHighlights.visibility = View.GONE
                    cardSetupHub.visibility = View.VISIBLE

                    // 1. Mock Location App Status
                    val isMockEnabled = PermissionHelper.isMockLocationEnabled(context)
                    if (isMockEnabled) {
                        ivDevStatusIcon.setImageResource(R.drawable.ic_check_circle)
                        ivDevStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                        tvDevStatusTitle.text = "Developer Mock Provider"
                        tvDevStatusDetail.text = "Nowhere is active in Developer Options."
                        btnDevAction.text = "Active"
                        btnDevAction.isEnabled = false
                        btnDevAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivDevStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivDevStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvDevStatusTitle.text = "Developer Mock Provider"
                        tvDevStatusDetail.text = "Select Nowhere in Developer Options."
                        btnDevAction.text = "Configure"
                        btnDevAction.isEnabled = true
                        btnDevAction.strokeColor = ContextCompat.getColorStateList(context, R.color.stroke_subtle)
                    }
                    btnDevAction.setOnClickListener {
                        SetupGuideDialog(context) {
                            PermissionHelper.openDeveloperSettings(context)
                        }.show()
                    }

                    // 2. System Permissions Status
                    val hasLoc = PermissionHelper.hasFineLocationPermission(context)
                    val hasNotif = PermissionHelper.hasNotificationPermission(context)
                    val isPermsGranted = hasLoc && hasNotif
                    if (isPermsGranted) {
                        ivPermStatusIcon.setImageResource(R.drawable.ic_check_circle)
                        ivPermStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                        tvPermStatusTitle.text = "System Permissions"
                        tvPermStatusDetail.text = "Location & notification access active."
                        btnPermAction.text = "Granted"
                        btnPermAction.isEnabled = false
                        btnPermAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivPermStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivPermStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvPermStatusTitle.text = "System Permissions"
                        tvPermStatusDetail.text = "Location & notification access required."
                        btnPermAction.text = "Grant"
                        btnPermAction.isEnabled = true
                        btnPermAction.strokeColor = ContextCompat.getColorStateList(context, R.color.stroke_subtle)
                    }
                    btnPermAction.setOnClickListener {
                        requestEssentialPermissions()
                    }

                    // 3. Battery Optimization Status
                    val isBatteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(context)
                    if (isBatteryExempt) {
                        ivBatteryStatusIcon.setImageResource(R.drawable.ic_check_circle)
                        ivBatteryStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                        tvBatteryStatusTitle.text = "Background Execution"
                        tvBatteryStatusDetail.text = "Unrestricted background running enabled."
                        btnBatteryAction.text = "Active"
                        btnBatteryAction.isEnabled = false
                        btnBatteryAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivBatteryStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivBatteryStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvBatteryStatusTitle.text = "Background Execution"
                        tvBatteryStatusDetail.text = "Prevent Android from sleeping GPS."
                        btnBatteryAction.text = "Allow"
                        btnBatteryAction.isEnabled = true
                        btnBatteryAction.strokeColor = ContextCompat.getColorStateList(context, R.color.stroke_subtle)
                    }
                    btnBatteryAction.setOnClickListener {
                        PermissionHelper.requestIgnoreBatteryOptimizations(context)
                    }

                } else {
                    cardFeatureHighlights.visibility = View.VISIBLE
                    cardSetupHub.visibility = View.GONE

                    if (step.highlights.size >= 3) {
                        ivHighlight1.setImageResource(step.highlights[0].iconRes)
                        tvHighlightTitle1.text = step.highlights[0].title
                        tvHighlightDesc1.text = step.highlights[0].desc

                        ivHighlight2.setImageResource(step.highlights[1].iconRes)
                        tvHighlightTitle2.text = step.highlights[1].title
                        tvHighlightDesc2.text = step.highlights[1].desc

                        ivHighlight3.setImageResource(step.highlights[2].iconRes)
                        tvHighlightTitle3.text = step.highlights[2].title
                        tvHighlightDesc3.text = step.highlights[2].desc
                    }
                }
            }
        }
    }
}

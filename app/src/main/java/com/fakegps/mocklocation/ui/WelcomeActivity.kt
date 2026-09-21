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
                badgeText = getString(R.string.welcome_badge_teleport),
                headlineText = getString(R.string.welcome_headline_teleport),
                descriptionText = getString(R.string.welcome_desc_teleport),
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_location_pin,
                        title = getString(R.string.welcome_highlight_coord_title),
                        desc = getString(R.string.welcome_highlight_coord_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_globe,
                        title = getString(R.string.welcome_highlight_maps_title),
                        desc = getString(R.string.welcome_highlight_maps_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_search,
                        title = getString(R.string.welcome_highlight_search_title),
                        desc = getString(R.string.welcome_highlight_search_desc)
                    )
                )
            ),
            SetupStep(
                type = StepType.PRESENTATION_ROUTES,
                iconRes = R.drawable.ic_route,
                badgeText = getString(R.string.welcome_badge_routes),
                headlineText = getString(R.string.welcome_headline_routes),
                descriptionText = getString(R.string.welcome_desc_routes),
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_route,
                        title = getString(R.string.welcome_highlight_waypoints_title),
                        desc = getString(R.string.welcome_highlight_waypoints_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_joystick,
                        title = getString(R.string.welcome_highlight_joystick_title),
                        desc = getString(R.string.welcome_highlight_joystick_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_gpx,
                        title = getString(R.string.welcome_highlight_gpx_title),
                        desc = getString(R.string.welcome_highlight_gpx_desc)
                    )
                )
            ),
            SetupStep(
                type = StepType.PRESENTATION_STEALTH,
                iconRes = R.drawable.ic_shield_check,
                badgeText = getString(R.string.welcome_badge_stealth),
                headlineText = getString(R.string.welcome_headline_stealth),
                descriptionText = getString(R.string.welcome_desc_stealth),
                highlights = listOf(
                    HighlightItem(
                        iconRes = R.drawable.ic_shield_check,
                        title = getString(R.string.welcome_highlight_fused_title),
                        desc = getString(R.string.welcome_highlight_fused_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_bolt,
                        title = getString(R.string.welcome_highlight_jitter_title),
                        desc = getString(R.string.welcome_highlight_jitter_desc)
                    ),
                    HighlightItem(
                        iconRes = R.drawable.ic_launcher_monochrome,
                        title = getString(R.string.welcome_highlight_ghost_title),
                        desc = getString(R.string.welcome_highlight_ghost_desc)
                    )
                )
            ),
            SetupStep(
                type = StepType.SETUP_HUB,
                iconRes = R.drawable.ic_settings,
                badgeText = getString(R.string.welcome_badge_hub),
                headlineText = getString(R.string.welcome_headline_hub),
                descriptionText = getString(R.string.welcome_desc_hub),
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
            binding.btnWalkthroughNext.text = getString(R.string.welcome_btn_next)
            binding.btnWalkthroughNext.setIconResource(R.drawable.ic_chevron_right)
            binding.btnWalkthroughNext.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card_elevated)
            binding.btnWalkthroughNext.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnWalkthroughNext.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.btnWalkthroughSkip.visibility = View.INVISIBLE
            binding.btnWalkthroughNext.text = getString(R.string.welcome_btn_enter)
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
                        tvDevStatusTitle.text = context.getString(R.string.welcome_dev_title)
                        tvDevStatusDetail.text = context.getString(R.string.welcome_dev_active_desc)
                        btnDevAction.text = context.getString(R.string.settings_status_active)
                        btnDevAction.isEnabled = false
                        btnDevAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivDevStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivDevStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvDevStatusTitle.text = context.getString(R.string.welcome_dev_title)
                        tvDevStatusDetail.text = context.getString(R.string.welcome_dev_needed_desc)
                        btnDevAction.text = context.getString(R.string.welcome_dev_btn_configure)
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
                        tvPermStatusTitle.text = context.getString(R.string.welcome_perms_title)
                        tvPermStatusDetail.text = context.getString(R.string.welcome_perms_active_desc)
                        btnPermAction.text = context.getString(R.string.settings_status_granted)
                        btnPermAction.isEnabled = false
                        btnPermAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivPermStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivPermStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvPermStatusTitle.text = context.getString(R.string.welcome_perms_title)
                        tvPermStatusDetail.text = context.getString(R.string.welcome_perms_needed_desc)
                        btnPermAction.text = context.getString(R.string.settings_status_grant)
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
                        tvBatteryStatusTitle.text = context.getString(R.string.welcome_battery_title)
                        tvBatteryStatusDetail.text = context.getString(R.string.welcome_battery_active_desc)
                        btnBatteryAction.text = context.getString(R.string.settings_status_active)
                        btnBatteryAction.isEnabled = false
                        btnBatteryAction.strokeColor = ContextCompat.getColorStateList(context, R.color.badge_success_bg)
                    } else {
                        ivBatteryStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                        ivBatteryStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                        tvBatteryStatusTitle.text = context.getString(R.string.welcome_battery_title)
                        tvBatteryStatusDetail.text = context.getString(R.string.welcome_battery_needed_desc)
                        btnBatteryAction.text = context.getString(R.string.welcome_battery_btn_allow)
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

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
        if (::walkthroughAdapter.isInitialized) {
            walkthroughAdapter.notifyDataSetChanged()
        }
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
            walkthroughAdapter.notifyDataSetChanged()
        }
    }

    private fun setupViewPager() {
        val steps = listOf(
            SetupStep(
                type = StepType.DEV_OPTIONS,
                iconRes = R.drawable.ic_settings,
                badgeText = "STEP 1 OF 3 • DEVELOPER OPTIONS",
                headlineText = "Select Nowhere in Developer Options",
                descriptionText = "Android requires Nowhere to be selected as your Mock Location App before GPS simulation can begin.",
                tipText = "💡 Tip: In Developer Options, find 'Select mock location app' and choose Nowhere. If Developer Options is hidden on your phone, go to Settings > About Phone and tap 'Build Number' 7 times."
            ),
            SetupStep(
                type = StepType.PERMISSIONS,
                iconRes = R.drawable.ic_my_location,
                badgeText = "STEP 2 OF 3 • SYSTEM PERMISSIONS",
                headlineText = "Grant Location & Notification Access",
                descriptionText = "Precise location is needed to initialize your coordinates, and notifications keep continuous background routes alive.",
                tipText = "🔒 Privacy: Nowhere never uploads or shares your location data. Permissions are strictly used for on-device OS-level GPS injection."
            ),
            SetupStep(
                type = StepType.BATTERY,
                iconRes = R.drawable.ic_bolt,
                badgeText = "STEP 3 OF 3 • BACKGROUND EXECUTION",
                headlineText = "Allow Unrestricted Background Running",
                descriptionText = "Prevent Android battery optimizations from putting GPS simulation to sleep when switching apps or locking your phone.",
                tipText = "⚡ Tip: Disabling battery restrictions ensures zero stutter during long route simulations and keeps joystick steering responsive."
            )
        )

        walkthroughAdapter = WalkthroughPagerAdapter(steps)
        binding.pagerWalkthrough.adapter = walkthroughAdapter

        binding.pagerWalkthrough.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                updateNavigationButtons(position)
            }
        })
    }

    private fun updateIndicator(position: Int) {
        val dots = listOf(binding.viewDot1, binding.viewDot2, binding.viewDot3)
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

        if (position < 2) {
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
            binding.pagerWalkthrough.setCurrentItem(2, true)
        }

        binding.btnWalkthroughNext.setOnClickListener {
            val current = binding.pagerWalkthrough.currentItem
            if (current < 2) {
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

    enum class StepType {
        DEV_OPTIONS,
        PERMISSIONS,
        BATTERY
    }

    data class SetupStep(
        val type: StepType,
        val iconRes: Int,
        val badgeText: String,
        val headlineText: String,
        val descriptionText: String,
        val tipText: String
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
                tvStepTip.text = step.tipText

                when (step.type) {
                    StepType.DEV_OPTIONS -> {
                        val isMockEnabled = PermissionHelper.isMockLocationEnabled(context)
                        if (isMockEnabled) {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_check_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                            tvStepStatusTitle.text = "Status: Configured & Active"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_success_text))
                            tvStepStatusDetail.text = "Nowhere is active in Developer Options."
                            btnStepAction.text = "Re-open Developer Options"
                            btnStepAction.isEnabled = true
                        } else {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                            tvStepStatusTitle.text = "Status: Action Required"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_warning_text))
                            tvStepStatusDetail.text = "Select Nowhere as your Mock Location App."
                            btnStepAction.text = "Open Developer Options"
                            btnStepAction.isEnabled = true
                        }
                        btnStepAction.setIconResource(R.drawable.ic_settings)
                        btnStepAction.setOnClickListener {
                            SetupGuideDialog(context) {
                                PermissionHelper.openDeveloperSettings(context)
                            }.show()
                        }
                    }

                    StepType.PERMISSIONS -> {
                        val hasLoc = PermissionHelper.hasFineLocationPermission(context)
                        val hasNotif = PermissionHelper.hasNotificationPermission(context)
                        val isGranted = hasLoc && hasNotif

                        if (isGranted) {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_check_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                            tvStepStatusTitle.text = "Status: Permissions Granted"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_success_text))
                            tvStepStatusDetail.text = "Precise location and notification access enabled."
                            btnStepAction.text = "Permissions Active"
                            btnStepAction.isEnabled = false
                        } else {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                            tvStepStatusTitle.text = "Status: Permissions Required"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_warning_text))
                            tvStepStatusDetail.text = "Grant location and notifications to proceed."
                            btnStepAction.text = "Grant Permissions"
                            btnStepAction.isEnabled = true
                        }
                        btnStepAction.setIconResource(R.drawable.ic_shield_check)
                        btnStepAction.setOnClickListener {
                            requestEssentialPermissions()
                        }
                    }

                    StepType.BATTERY -> {
                        val isExempt = PermissionHelper.isIgnoringBatteryOptimizations(context)
                        if (isExempt) {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_check_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_success_text)
                            tvStepStatusTitle.text = "Status: Unrestricted Running Active"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_success_text))
                            tvStepStatusDetail.text = "Background GPS simulation will not be throttled."
                            btnStepAction.text = "Battery Unrestricted"
                            btnStepAction.isEnabled = false
                        } else {
                            ivStepStatusIcon.setImageResource(R.drawable.ic_warning_circle)
                            ivStepStatusIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.badge_warning_text)
                            tvStepStatusTitle.text = "Status: Battery Optimized"
                            tvStepStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.badge_warning_text))
                            tvStepStatusDetail.text = "Android may sleep GPS simulation in the background."
                            btnStepAction.text = "Allow Unrestricted"
                            btnStepAction.isEnabled = true
                        }
                        btnStepAction.setIconResource(R.drawable.ic_bolt)
                        btnStepAction.setOnClickListener {
                            PermissionHelper.requestIgnoreBatteryOptimizations(context)
                        }
                    }
                }
            }
        }
    }
}

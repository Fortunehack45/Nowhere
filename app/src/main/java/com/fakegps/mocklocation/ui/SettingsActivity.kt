package com.fakegps.mocklocation.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivitySettingsBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updatePadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import android.widget.TextView
import android.widget.RadioButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshNotificationPermissionUI()
        if (isGranted) {
            Toast.makeText(this, "🔔 Notifications enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        sessionPrefs = SessionPreferences(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge system bar insets (Android 15+ & targetSdk 35)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())

            binding.layoutSettingsHeader.updatePadding(top = statusBarInset.top)

            val scrollView = binding.root.getChildAt(1) as? android.widget.ScrollView
            val scrollChild = scrollView?.getChildAt(0) as? android.widget.LinearLayout
            scrollChild?.setPadding(
                scrollChild.paddingLeft,
                scrollChild.paddingTop,
                scrollChild.paddingRight,
                (16 * resources.displayMetrics.density).toInt() + navBarInset.bottom
            )

            insets
        }

        loadInitialValues()
        setupListeners()
        observeSessionTimer()
        observeBillingState()

        if (!com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value) {
            com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = false)
        }
    }

    override fun onResume() {
        super.onResume()
        com.fakegps.mocklocation.billing.BillingManager.getInstance(this).onResume()
        if (sessionPrefs.hasValidActiveSession()) {
            com.fakegps.mocklocation.service.SessionTimerManager.resumeExistingTimer(this)
        }
        if (!com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value) {
            if (binding.adBannerContainer.childCount == 0) {
                com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = false)
            }
        } else {
            com.fakegps.mocklocation.ads.AdManager.clearBanner(binding.adBannerContainer)
        }
        refreshSystemStatus()
        refreshNotificationPermissionUI()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, this)
    }

    private fun refreshNotificationPermissionUI() {
        val isGranted = PermissionHelper.hasNotificationPermission(this)
        if (isGranted) {
            binding.tvNotificationPermissionStatus.text = "Allowed"
            binding.tvNotificationPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
        } else {
            binding.tvNotificationPermissionStatus.text = "Permission Required"
            binding.tvNotificationPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
        }
    }

    private fun observeBillingState() {
        val billingManager = com.fakegps.mocklocation.billing.BillingManager.getInstance(this)
        lifecycleScope.launch {
            billingManager.entitlementState.collect { entitlement ->
                if (!isFinishing && !isDestroyed) {
                    renderPremiumSettingsUI(entitlement, billingManager)
                }
            }
        }
    }

    private fun renderPremiumSettingsUI(
        entitlement: com.fakegps.mocklocation.billing.PremiumEntitlement,
        billingManager: com.fakegps.mocklocation.billing.BillingManager
    ) {
        val formattedPrice = entitlement.formattedPrice ?: billingManager.getFormattedPrice()
        if (entitlement.isPremium) {
            binding.tvSettingsPremiumTitle.text = "Nowhere Premium Active"
            binding.tvSettingsPremiumSubtitle.text = "Unlimited session duration & zero ads enabled"
            binding.btnSettingsPremiumAction.text = "MANAGE"
            binding.btnSettingsPremiumAction.setIconResource(R.drawable.ic_shield_check)
            binding.btnSettingsPremiumAction.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_success_bg)
            binding.btnSettingsPremiumAction.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            binding.btnSettingsPremiumAction.iconTint = ContextCompat.getColorStateList(this, R.color.badge_success_text)
            binding.ivSettingsPremiumIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_success_text)
            com.fakegps.mocklocation.ads.AdManager.clearBanner(binding.adBannerContainer)
        } else {
            val isVip = com.fakegps.mocklocation.billing.PromotionManager.isEligibleForVipDiscount(this)
            val discount = com.fakegps.mocklocation.billing.PromotionManager.getYearlyDiscountPercent(this)

            binding.tvSettingsPremiumTitle.text = "Nowhere Pro Engine"
            binding.tvSettingsPremiumSubtitle.text = when {
                isVip -> "🔥 VIP Offer: Save $discount% on Annual Pass • Unlimited & Zero Ads"
                entitlement.hasFreeTrial -> "✨ Free Trial Available • Unlimited duration & zero ads"
                formattedPrice != null -> "From $formattedPrice/mo • Unlimited duration & zero ads"
                else -> "Unlimited session duration & 100% zero ads"
            }
            binding.btnSettingsPremiumAction.text = if (isVip) "SAVE $discount%" else "UPGRADE"
            binding.btnSettingsPremiumAction.setIconResource(R.drawable.ic_bolt)
            binding.btnSettingsPremiumAction.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            binding.btnSettingsPremiumAction.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnSettingsPremiumAction.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.ivSettingsPremiumIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary_bright)
        }
    }

    private fun observeSessionTimer() {
        lifecycleScope.launch {
            com.fakegps.mocklocation.service.SessionTimerManager.timerState.collect { timerState ->
                if (!isFinishing && !isDestroyed) {
                    renderSessionTimerUI(timerState)
                }
            }
        }
    }

    private fun renderSessionTimerUI(timerState: com.fakegps.mocklocation.service.SessionTimerManager.SessionTimerState) {
        if (timerState.isUnlimited || sessionPrefs.isPremiumActive()) {
            binding.tvSettingsSessionBadge.text = "UNLIMITED"
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_success_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_success_text)

            binding.tvSettingsSessionTime.text = "UNLIMITED"
            binding.tvSettingsSessionTotal.text = "Premium Active: Unlimited Simulation"
            binding.pbSettingsSessionProgress.progress = 100
            return
        }

        val remaining = sessionPrefs.getTimeRemainingMillis()
        val formattedRemaining = sessionPrefs.formatRemainingTime()
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)
        val lightTintColor = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(this)
        val primaryCsl = ColorStateList.valueOf(primaryColor)
        val lightTintCsl = ColorStateList.valueOf(lightTintColor)

        binding.btnSettingsExtendOneHour.setTextColor(primaryColor)
        binding.btnSettingsExtendOneHour.iconTint = primaryCsl

        if (timerState.isRunning || (sessionPrefs.isSessionActive && remaining > 0)) {
            binding.tvSettingsSessionBadge.text = formattedRemaining
            binding.tvSettingsSessionBadge.setTextColor(primaryColor)
            binding.tvSettingsSessionBadge.backgroundTintList = lightTintCsl
            binding.ivSettingsSessionIcon.imageTintList = primaryCsl

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = "Total Allocated: ${sessionPrefs.formatAllocatedDuration()}"
            binding.pbSettingsSessionProgress.progress = timerState.progressPercent
            binding.pbSettingsSessionProgress.progressTintList = primaryCsl
        } else if (timerState.isExpired || sessionPrefs.isSessionExpired) {
            binding.tvSettingsSessionBadge.text = "EXPIRED"
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_error_text)

            binding.tvSettingsSessionTime.text = "00:00:00"
            binding.tvSettingsSessionTotal.text = "Session expired. Tap +2 Hours to extend."
            binding.pbSettingsSessionProgress.progress = 0
        } else {
            binding.tvSettingsSessionBadge.text = "STANDBY"
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_standby_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_text)

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = "Default Duration: 2h 00m (Ready)"
            binding.pbSettingsSessionProgress.progress = 100
        }
    }

    private fun refreshSystemStatus() {
        renderSessionTimerUI(com.fakegps.mocklocation.service.SessionTimerManager.timerState.value)

        // Mock Location in Developer Options
        val isMockEnabled = PermissionHelper.isMockLocationEnabled(this)
        if (isMockEnabled) {
            binding.tvSettingsMockStatus.text = "Nowhere is selected as mock location app"
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsDevOptions.text = "Configured"
        } else {
            binding.tvSettingsMockStatus.text = "Not selected as mock app in Developer Options"
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.btnSettingsDevOptions.text = "Select Nowhere"
        }

        // Battery Optimization
        val isBatteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(this)
        if (isBatteryExempt) {
            binding.tvSettingsBatteryStatus.text = "Unrestricted background running enabled"
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsBattery.text = "Active"
            binding.btnSettingsBattery.isEnabled = false
        } else {
            binding.tvSettingsBatteryStatus.text = "Battery optimizer may sleep background GPS"
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsBattery.text = "Allow Unrestricted"
            binding.btnSettingsBattery.isEnabled = true
        }

        // Floating Window Overlay
        val hasOverlay = PermissionHelper.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvSettingsOverlayStatus.text = "Overlay permission granted for floating joystick"
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsOverlay.text = "Granted"
            binding.btnSettingsOverlay.isEnabled = false
        } else {
            binding.tvSettingsOverlayStatus.text = "Permission needed for floating joystick overlay"
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsOverlay.text = "Grant"
            binding.btnSettingsOverlay.isEnabled = true
        }
    }

    private fun loadInitialValues() {
        // Advanced Simulation
        binding.switchFusedProvider.isChecked = settingsPrefs.useFusedProvider
        binding.switchJitter.isChecked = settingsPrefs.randomizeJitter
        val jitterRadius = settingsPrefs.jitterRadiusMeters.coerceIn(0.5f, 10.0f)
        binding.sliderJitterRadius.value = jitterRadius
        binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", jitterRadius)
        binding.sliderJitterRadius.isEnabled = settingsPrefs.randomizeJitter
        binding.sliderJitterRadius.alpha = if (settingsPrefs.randomizeJitter) 1.0f else 0.4f
        binding.tvJitterRadiusLabel.alpha = if (settingsPrefs.randomizeJitter) 1.0f else 0.4f

        when (settingsPrefs.truncateDecimals) {
            6 -> binding.rbTruncate6.isChecked = true
            4 -> binding.rbTruncate4.isChecked = true
            else -> binding.rbTruncateFull.isChecked = true
        }

        // Map Tiles & Visuals
        when (settingsPrefs.mapTileSource) {
            "TOPO" -> binding.rbTopo.isChecked = true
            "USGS_SAT" -> binding.rbUsgsSat.isChecked = true
            else -> binding.rbMapnik.isChecked = true
        }

        // Theme & Units
        when (settingsPrefs.appTheme) {
            "LIGHT" -> binding.rbThemeLight.isChecked = true
            "SYSTEM" -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        when (settingsPrefs.distanceUnit) {
            "IMPERIAL" -> binding.rbUnitImperial.isChecked = true
            else -> binding.rbUnitMetric.isChecked = true
        }

        binding.switchSettingsBootInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        binding.switchSettingsGhostCloak.isChecked = settingsPrefs.isGhostCloakEnabled
        binding.switchSettingsAutoVpnSync.isChecked = settingsPrefs.isAutoVpnSyncEnabled
        binding.btnResetDefaults.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
        binding.btnResetDefaults.rippleColor = ColorStateList.valueOf(com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(this))
        refreshThemeColorUI()
        refreshWidgetSlotsUI()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, this)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettingsPremiumAction.setOnClickListener {
            val isPremium = com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value
            if (isPremium) {
                com.fakegps.mocklocation.billing.BillingManager.getInstance(this).openManageSubscriptions(this)
            } else {
                com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.newInstance()
                    .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.TAG)
            }
        }

        binding.cardPremiumSettings.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.TAG)
        }

        binding.btnSettingsExtendOneHour.setOnClickListener {
            com.fakegps.mocklocation.ads.AdManager.showRewardVideoWithProgress(
                this,
                onUserEarnedReward = {
                    com.fakegps.mocklocation.service.SessionTimerManager.extendSession(this, SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)
                    Toast.makeText(this, "✅ +2 Hours Added! Simulation time extended.", Toast.LENGTH_SHORT).show()
                    refreshSystemStatus()
                },
                onAdClosed = {
                    refreshSystemStatus()
                }
            )
        }

        binding.btnSettingsExtendManage.setOnClickListener {
            val dialog = com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this)
            dialog.show()
        }

        // Permission & Integration buttons
        binding.btnSettingsAutoGrantRoot.setOnClickListener {
            val granted = PermissionHelper.tryAutoGrantRootMockPermission(this)
            if (granted) {
                Toast.makeText(this, "🎉 Mock Location Auto-Granted! Nowhere is ready.", Toast.LENGTH_LONG).show()
                refreshSystemStatus()
            } else {
                Toast.makeText(this, "👉 Scroll to 'Debugging' -> Tap 'Select mock location app' -> Choose Nowhere", Toast.LENGTH_LONG).show()
                PermissionHelper.openDeveloperSettings(this)
            }
        }

        binding.btnSettingsDevOptions.setOnClickListener {
            Toast.makeText(this, "👉 Scroll to 'Debugging' -> Tap 'Select mock location app' -> Choose Nowhere", Toast.LENGTH_LONG).show()
            PermissionHelper.openDeveloperSettings(this)
        }

        binding.btnSettingsBattery.setOnClickListener {
            PermissionHelper.requestIgnoreBatteryOptimizations(this)
        }

        binding.btnSettingsOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }

        binding.btnSettingsHotspotManage.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.TAG)
        }

        // Developer & Social Links
        binding.btnDeveloperPortfolio.setOnClickListener {
            openBrowser("https://fortuneadebayo.space")
        }

        binding.btnDeveloperTwitter.setOnClickListener {
            openBrowser("https://x.com/OnNerd_eth")
        }

        binding.btnTelegramChannel.setOnClickListener {
            openBrowser("https://t.me/nowhere_proxy")
        }

        binding.btnTelegramGroup.setOnClickListener {
            openBrowser("https://t.me/+vcmA7kOtLEw3ZjM0")
        }

        binding.btnDeveloperWhatsApp1.setOnClickListener {
            openBrowser("https://wa.me/2347067860584")
        }

        binding.btnDeveloperWhatsApp2.setOnClickListener {
            openBrowser("https://wa.me/2349167689200")
        }

        binding.btnOpenAppTutorial.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_START_SPOTLIGHT_TOUR", true)
            }
            startActivity(intent)
            finish()
        }

        binding.btnOpenDisclaimer.setOnClickListener {
            showDisclaimerDialog()
        }

        binding.btnOpenPrivacyPolicy.setOnClickListener {
            showDisclaimerDialog()
        }

        binding.btnNotificationPermissions.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionHelper.hasNotificationPermission(this)) {
                com.fakegps.mocklocation.ui.dialogs.NotificationPermissionDialog(
                    activity = this,
                    onRequestPermission = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ).show()
            } else {
                PermissionHelper.openAppNotificationSettings(this)
            }
        }

        binding.btnRateAppOnPlayStore.setOnClickListener {
            com.fakegps.mocklocation.util.AppReviewManager.launchReviewFlow(this, forcePrompt = true)
        }

        binding.tvAppVersionTitle.text = "Nowhere Version v${com.fakegps.mocklocation.BuildConfig.VERSION_NAME}"
        binding.tvSettingsFooterVersion.text = "Version ${com.fakegps.mocklocation.BuildConfig.VERSION_NAME} (Build ${com.fakegps.mocklocation.BuildConfig.VERSION_CODE}) • Release"
        binding.btnCheckAppUpdates.setOnClickListener {
            binding.tvCheckUpdateStatus.text = "Checking Google Play..."
            binding.btnCheckAppUpdates.isEnabled = false
            lifecycleScope.launch {
                val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.checkForUpdates(this@SettingsActivity)
                binding.btnCheckAppUpdates.isEnabled = true
                if (updateInfo.isUpdateAvailable && updateInfo.appUpdateInfo != null) {
                    binding.tvCheckUpdateStatus.text = "Update Available"
                    com.fakegps.mocklocation.util.AppUpdateManager.startPlayUpdateFlow(this@SettingsActivity, updateInfo.appUpdateInfo)
                } else {
                    binding.tvCheckUpdateStatus.text = "Up to date"
                    Toast.makeText(this@SettingsActivity, "You are running the latest version from Google Play (v${com.fakegps.mocklocation.BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Persistent Location Injector
        binding.switchSettingsBootInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Enabled" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Anti-Detection & Ghost Cloak Suite
        binding.switchSettingsGhostCloak.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isGhostCloakEnabled = isChecked
            val msg = if (isChecked) "Ghost Cloak: Active (Stealth Mode ON)" else "Ghost Cloak: Inactive"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchSettingsAutoVpnSync.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isAutoVpnSyncEnabled = isChecked
            val msg = if (isChecked) "Auto-Sync VPN: Enabled" else "Auto-Sync VPN: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnSettingsGhostCloakManage.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.AntiDetectionBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.AntiDetectionBottomSheet.TAG)
        }

        // Switches & Sliders
        binding.switchFusedProvider.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.useFusedProvider = isChecked
        }

        binding.switchJitter.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.randomizeJitter = isChecked
            binding.sliderJitterRadius.isEnabled = isChecked
            binding.sliderJitterRadius.alpha = if (isChecked) 1.0f else 0.4f
            binding.tvJitterRadiusLabel.alpha = if (isChecked) 1.0f else 0.4f
        }

        binding.sliderJitterRadius.addOnChangeListener { _, value, _ ->
            settingsPrefs.jitterRadiusMeters = value
            binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", value)
        }

        binding.rgTruncate.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.truncateDecimals = when (checkedId) {
                R.id.rbTruncate6 -> 6
                R.id.rbTruncate4 -> 4
                else -> -1
            }
        }

        binding.rgMapSource.setOnCheckedChangeListener { _, checkedId ->
            val source = when (checkedId) {
                R.id.rbTopo -> "TOPO"
                R.id.rbUsgsSat -> "USGS_SAT"
                else -> "MAPNIK"
            }
            settingsPrefs.mapTileSource = source
            val label = when (source) {
                "TOPO" -> "Terrain Topographic (OpenTopoMap)"
                "USGS_SAT" -> "High-Resolution Satellite Imagery"
                else -> "Standard OpenStreetMap"
            }
            Toast.makeText(this, "Map layer: $label", Toast.LENGTH_SHORT).show()
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbThemeLight -> "LIGHT"
                R.id.rbThemeSystem -> "SYSTEM"
                else -> "DARK"
            }
            if (settingsPrefs.appTheme != theme) {
                settingsPrefs.appTheme = theme
                settingsPrefs.applyTheme(theme)
                com.fakegps.mocklocation.util.ThemeColorManager.updateAllAppWidgets(this)
                Toast.makeText(this, "Theme set to $theme", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.distanceUnit = when (checkedId) {
                R.id.rbUnitImperial -> "IMPERIAL"
                else -> "METRIC"
            }
        }

        binding.btnPinShortcut.setOnClickListener {
            pinNowhereShortcut()
        }

        binding.layoutSettingsThemeColor.setOnClickListener {
            showThemeColorPickerDialog()
        }

        // Widget Slot Customization Buttons
        binding.btnEditWidgetSlot1.setOnClickListener { showEditSlotDialog(1) }
        binding.btnEditWidgetSlot2.setOnClickListener { showEditSlotDialog(2) }
        binding.btnEditWidgetSlot3.setOnClickListener { showEditSlotDialog(3) }
    }

    private fun refreshThemeColorUI() {
        val theme = com.fakegps.mocklocation.util.ThemeColorManager.getCurrentTheme(this)
        binding.tvSettingsThemeColorDesc.text = "${theme.displayName} (${theme.primaryColorHex})"
        binding.viewThemeColorDot.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
    }

    private fun showThemeColorPickerDialog() {
        val themes = com.fakegps.mocklocation.util.ThemeColorManager.THEMES
        val currentThemeId = settingsPrefs.appThemeColor

        val adapter = object : android.widget.ArrayAdapter<com.fakegps.mocklocation.util.ColorTheme>(
            this,
            R.layout.item_theme_color_option,
            themes
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_theme_color_option, parent, false)
                val item = getItem(position) ?: return row
                val viewColorCircle = row.findViewById<View>(R.id.viewColorCircle)
                val tvColorName = row.findViewById<TextView>(R.id.tvColorName)
                val rbSelected = row.findViewById<android.widget.RadioButton>(R.id.rbSelected)

                val colorInt = android.graphics.Color.parseColor(item.primaryColorHex)
                viewColorCircle.background = com.fakegps.mocklocation.util.ThemeColorManager.createCircleDrawable(colorInt)
                tvColorName.text = item.displayName
                rbSelected.isChecked = item.id.equals(currentThemeId, ignoreCase = true)
                rbSelected.buttonTintList = android.content.res.ColorStateList.valueOf(colorInt)

                return row
            }
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Choose App Theme Accent")
            .setAdapter(adapter, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            val chosenTheme = themes[position]
            settingsPrefs.appThemeColor = chosenTheme.id
            com.fakegps.mocklocation.util.ThemeColorManager.setAppThemeColor(this, chosenTheme.id)
            com.fakegps.mocklocation.util.ThemeColorManager.updateAllAppWidgets(this)
            Toast.makeText(this, "App Theme Updated to ${chosenTheme.displayName}!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            recreate()
        }

        dialog.show()
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(primaryColor)
    }

    private fun refreshWidgetSlotsUI() {
        binding.tvWidgetSlot1Title.text = "Slot 1: ${settingsPrefs.widgetSlot1Name}"
        binding.tvWidgetSlot1Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot1Lat, settingsPrefs.widgetSlot1Lon)

        binding.tvWidgetSlot2Title.text = "Slot 2: ${settingsPrefs.widgetSlot2Name}"
        binding.tvWidgetSlot2Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot2Lat, settingsPrefs.widgetSlot2Lon)

        binding.tvWidgetSlot3Title.text = "Slot 3: ${settingsPrefs.widgetSlot3Name}"
        binding.tvWidgetSlot3Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot3Lat, settingsPrefs.widgetSlot3Lon)
    }

    private fun showEditSlotDialog(slotIndex: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_widget_slot, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogSlotTitle)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotName)
        val etLat = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotLat)
        val etLon = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotLon)
        val btnLookup = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLookupCoords)
        val tvGeocodingStatus = dialogView.findViewById<android.widget.TextView>(R.id.tvGeocodingStatus)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelSlot)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveSlot)

        tvTitle.text = "EDIT WIDGET DESTINATION (SLOT $slotIndex)"

        when (slotIndex) {
            1 -> {
                etName.setText(settingsPrefs.widgetSlot1Name)
                etLat.setText(settingsPrefs.widgetSlot1Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot1Lon.toString())
            }
            2 -> {
                etName.setText(settingsPrefs.widgetSlot2Name)
                etLat.setText(settingsPrefs.widgetSlot2Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot2Lon.toString())
            }
            3 -> {
                etName.setText(settingsPrefs.widgetSlot3Name)
                etLat.setText(settingsPrefs.widgetSlot3Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot3Lon.toString())
            }
        }

        btnLookup.setOnClickListener {
            val query = etName.text.toString().trim()
            if (query.isBlank()) {
                tvGeocodingStatus.text = "⚠️ Please enter a city or country name first"
                tvGeocodingStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.badge_warning_text))
                return@setOnClickListener
            }
            tvGeocodingStatus.text = "🔍 Searching coordinates for \"$query\"..."
            tvGeocodingStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.primary))
            btnLookup.isEnabled = false

            resolvePlaceCoordinates(query) { lat, lon, resolvedName ->
                btnLookup.isEnabled = true
                if (lat != null && lon != null) {
                    etLat.setText(String.format(java.util.Locale.US, "%.4f", lat))
                    etLon.setText(String.format(java.util.Locale.US, "%.4f", lon))
                    tvGeocodingStatus.text = "📍 Auto-filled: ${resolvedName ?: query} (${String.format(java.util.Locale.US, "%.4f", lat)}, ${String.format(java.util.Locale.US, "%.4f", lon)})"
                    tvGeocodingStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.badge_success_text))
                } else {
                    tvGeocodingStatus.text = "⚠️ Could not auto-detect. Please check spelling or enter coordinates manually."
                    tvGeocodingStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.badge_warning_text))
                }
            }
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim().ifBlank { "Destination $slotIndex" }
            val lat = etLat.text.toString().toDoubleOrNull() ?: 0.0
            val lon = etLon.text.toString().toDoubleOrNull() ?: 0.0

            when (slotIndex) {
                1 -> {
                    settingsPrefs.widgetSlot1Name = name
                    settingsPrefs.widgetSlot1Lat = lat
                    settingsPrefs.widgetSlot1Lon = lon
                }
                2 -> {
                    settingsPrefs.widgetSlot2Name = name
                    settingsPrefs.widgetSlot2Lat = lat
                    settingsPrefs.widgetSlot2Lon = lon
                }
                3 -> {
                    settingsPrefs.widgetSlot3Name = name
                    settingsPrefs.widgetSlot3Lat = lat
                    settingsPrefs.widgetSlot3Lon = lon
                }
            }

            refreshWidgetSlotsUI()
            com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(this)
            Toast.makeText(this, "Slot $slotIndex updated to $name", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun resolvePlaceCoordinates(query: String, onResult: (Double?, Double?, String?) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var lat: Double? = null
            var lon: Double? = null
            var resolvedName: String? = null

            // 1. Android Geocoder
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(this@SettingsActivity, java.util.Locale.US)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        val lock = Object()
                        geocoder.getFromLocationName(query, 1) { addresses ->
                            synchronized(lock) {
                                if (!addresses.isNullOrEmpty()) {
                                    lat = addresses[0].latitude
                                    lon = addresses[0].longitude
                                    resolvedName = addresses[0].locality ?: addresses[0].featureName ?: addresses[0].countryName
                                }
                                lock.notifyAll()
                            }
                        }
                        synchronized(lock) {
                            if (lat == null) lock.wait(1000)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocationName(query, 1)
                        if (!addresses.isNullOrEmpty()) {
                            lat = addresses[0].latitude
                            lon = addresses[0].longitude
                            resolvedName = addresses[0].locality ?: addresses[0].featureName ?: addresses[0].countryName
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 2. OpenStreetMap Nominatim fallback
            if (lat == null || lon == null) {
                try {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val url = java.net.URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=1")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "NowhereWidgetGeocoder/1.0 (Android)")
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = org.json.JSONArray(body)
                        if (arr.length() > 0) {
                            val first = arr.getJSONObject(0)
                            lat = first.optString("lat").toDoubleOrNull()
                            lon = first.optString("lon").toDoubleOrNull()
                            resolvedName = first.optString("display_name").substringBefore(",")
                        }
                    }
                } catch (ignored: Exception) {}
            }

            withContext(Dispatchers.Main) {
                onResult(lat, lon, resolvedName)
            }
        }
    }

    private fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link: $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToDefaults() {
        settingsPrefs.appThemeColor = "RED"
        settingsPrefs.useFusedProvider = true
        settingsPrefs.randomizeJitter = false
        settingsPrefs.jitterRadiusMeters = 2.0f
        settingsPrefs.truncateDecimals = -1
        settingsPrefs.baseAccuracy = 1.5f
        settingsPrefs.defaultAltitude = 15.0f
        settingsPrefs.randomizeAltitude = false
        settingsPrefs.updateIntervalMovingMs = 1000L
        settingsPrefs.updateIntervalStationaryMs = 1000L
        settingsPrefs.mapTileSource = "MAPNIK"
        settingsPrefs.enableMapAnimations = true
        settingsPrefs.appTheme = "DARK"
        settingsPrefs.distanceUnit = "METRIC"
        settingsPrefs.enableHapticFeedback = true

        settingsPrefs.isGhostCloakEnabled = true
        settingsPrefs.isNmeaSynthesisEnabled = true
        settingsPrefs.isClockDriftEmulationEnabled = true
        settingsPrefs.isSensorKinematicsEnabled = true
        settingsPrefs.isAutoVpnSyncEnabled = true

        sessionPrefs.isPersistentBootInjectionEnabled = true
        sessionPrefs.autoMatchIpWithGps = true
        sessionPrefs.isKillSwitchEnabled = false
        sessionPrefs.isIpMaskingEnabled = false
        sessionPrefs.activeIpNodeId = "us_nyc"

        settingsPrefs.widgetSlot1Name = "Paris"
        settingsPrefs.widgetSlot1Lat = 48.8566
        settingsPrefs.widgetSlot1Lon = 2.3522

        settingsPrefs.widgetSlot2Name = "Tokyo"
        settingsPrefs.widgetSlot2Lat = 35.6762
        settingsPrefs.widgetSlot2Lon = 139.6503

        settingsPrefs.widgetSlot3Name = "New York"
        settingsPrefs.widgetSlot3Lat = 40.7128
        settingsPrefs.widgetSlot3Lon = -74.0060

        com.fakegps.mocklocation.util.ThemeColorManager.setAppThemeColor(this, "RED")
        com.fakegps.mocklocation.util.ThemeColorManager.updateAllAppWidgets(this)

        loadInitialValues()

        // Restart activity cleanly without savedInstanceState so all views reset
        val restartIntent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        finish()
        overridePendingTransition(0, 0)
        startActivity(restartIntent)
        overridePendingTransition(0, 0)
    }

    private fun pinNowhereShortcut() {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, "nowhere_main_launcher_shortcut")
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setShortLabel("Nowhere")
                .setLongLabel("Nowhere Location Simulator")
                .setIntent(shortcutIntent)
                .build()

            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
            Toast.makeText(this, "Nowhere icon added to home screen!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Home screen shortcut pinning not supported on this launcher", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDisclaimerDialog() {
        val disclaimerSheet = com.fakegps.mocklocation.ui.dialogs.DisclaimerBottomSheet()
        disclaimerSheet.show(supportFragmentManager, "DisclaimerBottomSheet")
    }
}

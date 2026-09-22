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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import android.widget.TextView
import android.widget.RadioButton
import android.content.Context
import com.fakegps.mocklocation.util.LocaleHelper
import com.fakegps.mocklocation.ui.dialogs.LanguageSelectorBottomSheet

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences
    private var currentLanguageCode: String = ""

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshNotificationPermissionUI()
        if (isGranted) {
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLanguageCode = LocaleHelper.getSelectedLanguage(this)
        settingsPrefs = AppSettingsPreferences(this)
        sessionPrefs = SessionPreferences(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBarInsets()

        val restoreY = intent.getIntExtra("EXTRA_RESTORE_SCROLL_Y", 0)
        if (restoreY > 0) {
            binding.scrollViewSettings.post {
                binding.scrollViewSettings.scrollY = restoreY
            }
        }

        loadInitialValues()
        setupListeners()
        observeSessionTimer()
        observeBillingState()

        if (!com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value) {
            com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = false)
        }

        lifecycleScope.launch {
            com.fakegps.mocklocation.util.LocaleHelper.languageChangeFlow.collectLatest { langCode ->
                if (langCode != currentLanguageCode && !isRestartingForLanguage) {
                    currentLanguageCode = langCode
                    val locale = java.util.Locale.forLanguageTag(langCode)
                    com.fakegps.mocklocation.util.LocaleHelper.updateResources(this@SettingsActivity, locale)
                    restartActivityCleanly()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val activeLang = LocaleHelper.getSelectedLanguage(this)
        if ((activeLang != currentLanguageCode || LocaleHelper.isLanguageStale) && !isRestartingForLanguage) {
            LocaleHelper.isLanguageStale = false
            currentLanguageCode = activeLang
            val locale = java.util.Locale.forLanguageTag(activeLang)
            LocaleHelper.updateResources(this, locale)
            restartActivityCleanly()
            return
        }
        com.fakegps.mocklocation.billing.BillingManager.getInstance(this).onResume()
        val isSimRunning = isSimulationRunningCompat()
        if (isSimRunning) {
            com.fakegps.mocklocation.service.SessionTimerManager.resumeExistingTimer(this)
        } else {
            com.fakegps.mocklocation.service.SessionTimerManager.updateStaticState(this)
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

    private fun isSimulationRunningCompat(): Boolean {
        if (com.fakegps.mocklocation.service.MockLocationService.isSimulationRunning()) return true
        val svc = com.fakegps.mocklocation.service.MockLocationService.activeInstance ?: com.fakegps.mocklocation.service.MockLocationServiceReceiver.activeService
        if (svc != null) {
            if (svc.isSimulationPaused) return false
            val state = svc.serviceState.value
            if (state is com.fakegps.mocklocation.service.ServiceState.Running && state.isPaused) return false
            return svc.activeMode !is com.fakegps.mocklocation.simulator.SimulationMode.Idle || state is com.fakegps.mocklocation.service.ServiceState.Running
        }
        return sessionPrefs.isSessionActive
    }

    private fun refreshNotificationPermissionUI() {
        val isGranted = PermissionHelper.hasNotificationPermission(this)
        if (isGranted) {
            binding.tvNotificationPermissionStatus.text = getString(R.string.status_allowed)
            binding.tvNotificationPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
        } else {
            binding.tvNotificationPermissionStatus.text = getString(R.string.status_permission_required)
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
            binding.tvSettingsPremiumTitle.text = getString(R.string.settings_premium_active_title)
            binding.tvSettingsPremiumSubtitle.text = getString(R.string.settings_premium_active_subtitle)
            binding.btnSettingsPremiumAction.text = getString(R.string.btn_manage)
            binding.btnSettingsPremiumAction.setIconResource(R.drawable.ic_shield_check)
            binding.btnSettingsPremiumAction.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_success_bg)
            binding.btnSettingsPremiumAction.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            binding.btnSettingsPremiumAction.iconTint = ContextCompat.getColorStateList(this, R.color.badge_success_text)
            binding.ivSettingsPremiumIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_success_text)
            com.fakegps.mocklocation.ads.AdManager.clearBanner(binding.adBannerContainer)
        } else {
            val isVip = com.fakegps.mocklocation.billing.PromotionManager.isEligibleForVipDiscount(this)
            val discount = com.fakegps.mocklocation.billing.PromotionManager.getYearlyDiscountPercent(this)

            binding.tvSettingsPremiumTitle.text = getString(R.string.settings_pro_engine)
            binding.tvSettingsPremiumSubtitle.text = when {
                isVip -> getString(R.string.settings_vip_offer_fmt, discount)
                entitlement.hasFreeTrial -> getString(R.string.settings_free_trial_fmt)
                formattedPrice != null -> getString(R.string.settings_from_price_fmt, formattedPrice)
                else -> getString(R.string.settings_unlimited_zero_ads)
            }
            binding.btnSettingsPremiumAction.text = if (isVip) getString(R.string.btn_save_discount_fmt, discount) else getString(R.string.btn_upgrade)
            binding.btnSettingsPremiumAction.setIconResource(R.drawable.ic_bolt)
            val primaryCsl = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
            binding.btnSettingsPremiumAction.backgroundTintList = primaryCsl
            binding.btnSettingsPremiumAction.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnSettingsPremiumAction.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.ivSettingsPremiumIcon.imageTintList = primaryCsl
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
            binding.tvSettingsSessionBadge.text = getString(R.string.status_unlimited)
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_success_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_success_text)

            binding.tvSettingsSessionTime.text = getString(R.string.status_unlimited)
            binding.tvSettingsSessionTotal.text = getString(R.string.status_premium_active_unlimited)
            binding.pbSettingsSessionProgress.progress = 100
            return
        }

        val remaining = timerState.remainingMillis
        val formattedRemaining = timerState.formattedRemaining
        val formattedTotal = timerState.formattedTotal
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)
        val lightTintColor = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(this)
        val primaryCsl = ColorStateList.valueOf(primaryColor)
        val lightTintCsl = ColorStateList.valueOf(lightTintColor)

        binding.btnSettingsExtendOneHour.setTextColor(primaryColor)
        binding.btnSettingsExtendOneHour.iconTint = primaryCsl

        val isSimRunning = isSimulationRunningCompat()
        if (isSimRunning && (timerState.isRunning || remaining > 0)) {
            binding.tvSettingsSessionBadge.text = formattedRemaining
            binding.tvSettingsSessionBadge.setTextColor(primaryColor)
            binding.tvSettingsSessionBadge.backgroundTintList = lightTintCsl
            binding.ivSettingsSessionIcon.imageTintList = primaryCsl

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = getString(R.string.status_total_allocated_fmt, formattedTotal)
            binding.pbSettingsSessionProgress.progress = timerState.progressPercent
            binding.pbSettingsSessionProgress.progressTintList = primaryCsl
        } else if (timerState.isExpired || remaining <= 0L) {
            binding.tvSettingsSessionBadge.text = getString(R.string.status_expired)
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_error_text)

            binding.tvSettingsSessionTime.text = "00:00:00"
            binding.tvSettingsSessionTotal.text = getString(R.string.status_session_expired_hint)
            binding.pbSettingsSessionProgress.progress = 0
        } else {
            binding.tvSettingsSessionBadge.text = getString(R.string.status_standby)
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_standby_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_text)

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = getString(R.string.status_duration_remaining_ready_fmt, formattedRemaining)
            binding.pbSettingsSessionProgress.progress = timerState.progressPercent
            binding.pbSettingsSessionProgress.progressTintList = primaryCsl
        }
    }

    private fun refreshSystemStatus() {
        renderSessionTimerUI(com.fakegps.mocklocation.service.SessionTimerManager.timerState.value)

        // Mock Location in Developer Options
        val isMockEnabled = PermissionHelper.isMockLocationEnabled(this)
        if (isMockEnabled) {
            binding.tvSettingsMockStatus.text = getString(R.string.settings_mock_selected)
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsDevOptions.text = getString(R.string.settings_configured)
        } else {
            binding.tvSettingsMockStatus.text = getString(R.string.settings_mock_not_selected)
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.btnSettingsDevOptions.text = getString(R.string.settings_select_nowhere)
        }

        // Battery Optimization
        val isBatteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(this)
        if (isBatteryExempt) {
            binding.tvSettingsBatteryStatus.text = getString(R.string.settings_battery_unrestricted)
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsBattery.text = getString(R.string.settings_status_active)
            binding.btnSettingsBattery.isEnabled = false
        } else {
            binding.tvSettingsBatteryStatus.text = getString(R.string.settings_battery_warning)
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsBattery.text = getString(R.string.settings_allow_unrestricted)
            binding.btnSettingsBattery.isEnabled = true
        }

        // Floating Window Overlay
        val hasOverlay = PermissionHelper.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvSettingsOverlayStatus.text = getString(R.string.settings_overlay_granted)
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsOverlay.text = getString(R.string.settings_status_granted)
            binding.btnSettingsOverlay.isEnabled = false
        } else {
            binding.tvSettingsOverlayStatus.text = getString(R.string.settings_overlay_needed)
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsOverlay.text = getString(R.string.settings_status_grant)
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

        val currentLanguage = LocaleHelper.getSelectedLanguageItem(this)
        binding.tvSettingsLanguageCurrent.text = "${currentLanguage.nativeName} ${currentLanguage.flagEmoji}"
        binding.tvSettingsLanguageCurrent.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
        binding.tvSettingsLanguageCurrent.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
        binding.ivSettingsLanguageIcon.imageTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)

        binding.switchSettingsBootInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        binding.switchSettingsGhostCloak.isChecked = settingsPrefs.isGhostCloakEnabled
        binding.switchSettingsAutoVpnSync.isChecked = sessionPrefs.isKillSwitchEnabled
        binding.switchRecentsShield.isChecked = settingsPrefs.isRecentsShieldEnabled
        binding.btnResetDefaults.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
        binding.btnResetDefaults.rippleColor = ColorStateList.valueOf(com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(this))
        refreshThemeColorUI()
        refreshWidgetSlotsUI()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, this)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.layoutSettingsLanguage.setOnClickListener {
            val sheet = LanguageSelectorBottomSheet.newInstance()
            sheet.onLanguageChanged = {
                val activeLang = LocaleHelper.getSelectedLanguage(this)
                if (activeLang != currentLanguageCode && !isRestartingForLanguage) {
                    currentLanguageCode = activeLang
                    val locale = java.util.Locale.forLanguageTag(activeLang)
                    LocaleHelper.updateResources(this, locale)
                    restartActivityCleanly()
                }
            }
            sheet.show(supportFragmentManager, LanguageSelectorBottomSheet.TAG)
        }

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

        binding.switchRecentsShield.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                settingsPrefs.isRecentsShieldEnabled = isChecked
                if (!isChecked) {
                    com.fakegps.mocklocation.util.RecentsShieldManager.ensureVisibleInRecents(this)
                } else {
                    val isRunning = isSimulationRunningCompat()
                    com.fakegps.mocklocation.util.RecentsShieldManager.updateRecentsShield(this, isRunning)
                }
                val msg = if (isChecked) "Recents Shield Active: Nowhere will be hidden from Recents while simulating" else "Nowhere will always appear in Recent Apps"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
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
            binding.tvCheckUpdateStatus.text = getString(R.string.settings_checking_updates)
            binding.btnCheckAppUpdates.isEnabled = false
            lifecycleScope.launch {
                val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.checkForUpdates(this@SettingsActivity)
                binding.btnCheckAppUpdates.isEnabled = true
                if (updateInfo.isUpdateAvailable && updateInfo.appUpdateInfo != null) {
                    binding.tvCheckUpdateStatus.text = getString(R.string.settings_update_available)
                    com.fakegps.mocklocation.util.AppUpdateManager.startPlayUpdateFlow(this@SettingsActivity, updateInfo.appUpdateInfo)
                } else {
                    binding.tvCheckUpdateStatus.text = getString(R.string.settings_up_to_date)
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
            val vpnIntent = android.net.VpnService.prepare(this)
            if (isChecked && vpnIntent != null) {
                com.fakegps.mocklocation.ui.dialogs.IpChangerBottomSheet.newInstance()
                    .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.IpChangerBottomSheet.TAG)
            } else {
                com.fakegps.mocklocation.vpn.KillSwitchManager.setEnabled(this, isChecked)
                val msg = if (isChecked) "Kill Switch Armed: Leak Protection ON" else "Kill Switch: Disabled"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
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

        val currentBlur = settingsPrefs.frostedGlassBlurPercent.toFloat().coerceIn(50f, 100f)
        binding.sliderFrostedGlassBlur.value = currentBlur
        binding.tvFrostedGlassBlurPercent.text = "${currentBlur.toInt()}%"

        binding.sliderFrostedGlassBlur.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            settingsPrefs.frostedGlassBlurPercent = percent
            binding.tvFrostedGlassBlurPercent.text = "$percent%"
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
        binding.tvSettingsLanguageCurrent.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
        binding.tvSettingsLanguageCurrent.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
        binding.ivSettingsLanguageIcon.imageTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
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
            refreshThemeColorUI()
            refreshSystemStatus()
            refreshWidgetSlotsUI()
            com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, this)
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
            tvGeocodingStatus.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this@SettingsActivity))
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

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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
        settingsPrefs.isRecentsShieldEnabled = false

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
        refreshSystemStatus()
        refreshNotificationPermissionUI()
    }

    private fun setupSystemBarInsets() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())

            binding.layoutSettingsHeader.updatePadding(top = statusBarInset.top)

            val scrollView = binding.scrollViewSettings
            val scrollChild = scrollView.getChildAt(0) as? android.widget.LinearLayout
            scrollChild?.setPadding(
                scrollChild.paddingLeft,
                scrollChild.paddingTop,
                scrollChild.paddingRight,
                (16 * resources.displayMetrics.density).toInt() + navBarInset.bottom
            )

            insets
        }
        androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)?.let { insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            binding.layoutSettingsHeader.updatePadding(top = statusBarInset.top)
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private var isRestartingForLanguage = false

    private fun restartActivityCleanly() {
        if (isRestartingForLanguage || isFinishing || isDestroyed) return
        isRestartingForLanguage = true
        val scrollY = try { binding.scrollViewSettings.scrollY } catch (e: Exception) { 0 }
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra("EXTRA_RESTORE_SCROLL_Y", scrollY)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        startActivity(intent)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val activeLang = LocaleHelper.getSelectedLanguage(this)
        if (activeLang != currentLanguageCode && !isRestartingForLanguage) {
            currentLanguageCode = activeLang
            val locale = java.util.Locale.forLanguageTag(activeLang)
            LocaleHelper.updateResources(this, locale)
            restartActivityCleanly()
        }
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

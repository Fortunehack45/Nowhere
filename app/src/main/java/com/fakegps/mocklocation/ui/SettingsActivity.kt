package com.fakegps.mocklocation.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivitySettingsBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        sessionPrefs = SessionPreferences(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadInitialValues()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    private fun refreshSystemStatus() {
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
        binding.sliderJitterRadius.value = settingsPrefs.jitterRadiusMeters.coerceIn(0.5f, 10.0f)
        binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", settingsPrefs.jitterRadiusMeters)

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
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        // Permission & Integration buttons
        binding.btnSettingsDevOptions.setOnClickListener {
            SetupGuideDialog(this) {
                PermissionHelper.openDeveloperSettings(this)
            }.show()
        }

        binding.btnSettingsBattery.setOnClickListener {
            PermissionHelper.requestIgnoreBatteryOptimizations(this)
        }

        binding.btnSettingsOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }

        // Developer & Social Links
        binding.btnDeveloperPortfolio.setOnClickListener {
            openBrowser("https://fortuneadebayo.space")
        }

        binding.btnDeveloperTwitter.setOnClickListener {
            openBrowser("https://x.com/OnNerd_eth")
        }

        binding.btnDeveloperWhatsApp1.setOnClickListener {
            openBrowser("https://wa.me/2347067860584")
        }

        binding.btnDeveloperWhatsApp2.setOnClickListener {
            openBrowser("https://wa.me/2349167689200")
        }

        // Persistent Location Injector
        binding.switchSettingsBootInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Enabled" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Switches & Sliders
        binding.switchFusedProvider.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.useFusedProvider = isChecked
        }

        binding.switchJitter.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.randomizeJitter = isChecked
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
            settingsPrefs.mapTileSource = when (checkedId) {
                R.id.rbTopo -> "TOPO"
                R.id.rbUsgsSat -> "USGS_SAT"
                else -> "MAPNIK"
            }
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
                Toast.makeText(this, "Theme set to $theme", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.distanceUnit = when (checkedId) {
                R.id.rbUnitImperial -> "IMPERIAL"
                else -> "METRIC"
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
        settingsPrefs.useFusedProvider = true
        settingsPrefs.randomizeJitter = true
        settingsPrefs.jitterRadiusMeters = 2.0f
        settingsPrefs.truncateDecimals = -1
        settingsPrefs.baseAccuracy = 2.5f
        settingsPrefs.defaultAltitude = 15.0f
        settingsPrefs.randomizeAltitude = true
        settingsPrefs.updateIntervalMovingMs = 1000L
        settingsPrefs.mapTileSource = "MAPNIK"
        settingsPrefs.enableMapAnimations = true
        settingsPrefs.appTheme = "DARK"
        settingsPrefs.distanceUnit = "METRIC"
        settingsPrefs.enableHapticFeedback = true

        loadInitialValues()
    }
}

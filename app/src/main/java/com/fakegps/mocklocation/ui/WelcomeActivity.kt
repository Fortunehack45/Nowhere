package com.fakegps.mocklocation.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivityWelcomeBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var sessionPrefs: SessionPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshReadinessStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionPrefs = SessionPreferences(this)

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animateEntry()
        setupListeners()
        requestEssentialPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshReadinessStatus()
    }

    private fun animateEntry() {
        binding.ivWelcomeLogo.scaleX = 0.7f
        binding.ivWelcomeLogo.scaleY = 0.7f
        binding.ivWelcomeLogo.alpha = 0.0f

        binding.ivWelcomeLogo.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator())
            .start()
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

    private fun refreshReadinessStatus() {
        val isMockEnabled = PermissionHelper.isMockLocationEnabled(this)
        val hasLocation = PermissionHelper.hasFineLocationPermission(this)
        val hasNotifications = PermissionHelper.hasNotificationPermission(this)

        // Mock Provider Check
        if (isMockEnabled) {
            binding.tvCheckMockProvider.text = "✅"
            binding.tvMockProviderStatus.text = "Mock Location App Active in Developer Options"
            binding.btnFixDeveloperSettings.text = "Developer Options Configured"
        } else {
            binding.tvCheckMockProvider.text = "⚠️"
            binding.tvMockProviderStatus.text = "Mock Location App Not Selected in Developer Options"
            binding.btnFixDeveloperSettings.text = "Select Nowhere in Developer Options"
        }

        // Permissions Check
        if (hasLocation && hasNotifications) {
            binding.tvCheckPermissions.text = "✅"
            binding.tvPermissionStatus.text = "Location & Notification Permissions Granted"
        } else {
            binding.tvCheckPermissions.text = "⚠️"
            binding.tvPermissionStatus.text = "Required Runtime Permissions Incomplete"
        }
    }

    private fun setupListeners() {
        binding.btnFixDeveloperSettings.setOnClickListener {
            SetupGuideDialog(this) {
                PermissionHelper.openDeveloperSettings(this)
            }.show()
        }

        binding.btnGetStarted.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}

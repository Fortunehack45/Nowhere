package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.billing.BillingManager
import com.fakegps.mocklocation.billing.PremiumEntitlement
import com.fakegps.mocklocation.billing.PromotionManager
import com.fakegps.mocklocation.databinding.LayoutBottomSheetPremiumBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Luxury Nowhere Pro HUD Paywall Bottom Sheet.
 * Features dual plan selection (Monthly with Free Trial vs Annual with 10% / 15% VIP discount),
 * real-time localized pricing, Google Play Billing 8.x checkout, lifecycle resilience,
 * and seamless fallback diagnostics.
 */
class PremiumBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "PremiumBottomSheet"

        fun newInstance(): PremiumBottomSheet {
            return PremiumBottomSheet()
        }
    }

    private var _binding: LayoutBottomSheetPremiumBinding? = null
    private val binding get() = _binding!!

    private var selectedPlan: PremiumEntitlement.PlanType = PremiumEntitlement.PlanType.YEARLY

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutBottomSheetPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val billingManager = BillingManager.getInstance(requireContext())

        setupPlanSelection()
        setupListeners(billingManager)
        observeBillingState(billingManager)

        // Ensure products are proactively refreshed from Google Play upon sheet entry
        billingManager.refreshProductDetails()
    }

    override fun onResume() {
        super.onResume()
        context?.let { ctx ->
            BillingManager.getInstance(ctx).refreshProductDetails()
        }
    }

    private fun setupPlanSelection() {
        binding.cardPlanYearly.setOnClickListener {
            if (selectedPlan != PremiumEntitlement.PlanType.YEARLY) {
                selectedPlan = PremiumEntitlement.PlanType.YEARLY
                renderPlanSelectionUI()
            }
        }

        binding.cardPlanMonthly.setOnClickListener {
            if (selectedPlan != PremiumEntitlement.PlanType.MONTHLY) {
                selectedPlan = PremiumEntitlement.PlanType.MONTHLY
                renderPlanSelectionUI()
            }
        }

        renderPlanSelectionUI()
    }

    private fun renderPlanSelectionUI() {
        val context = context ?: return
        val isYearly = selectedPlan == PremiumEntitlement.PlanType.YEARLY
        val discountPercent = PromotionManager.getYearlyDiscountPercent(context)
        val entitlement = BillingManager.getInstance(context).entitlementState.value

        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
        val lightTintColor = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(context)
        val primaryCsl = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(context)
        binding.btnUpgradePremium.backgroundTintList = primaryCsl

        binding.tvYearlyDiscountBadge.background = com.fakegps.mocklocation.util.ThemeColorManager.createDiscountBadgeDrawable(lightTintColor, context)
        binding.tvYearlyDiscountBadge.setTextColor(primaryColor)

        if (isYearly) {
            binding.cardPlanYearly.background = com.fakegps.mocklocation.util.ThemeColorManager.createSelectedPlanCardDrawable(primaryColor, context)
            binding.ivRadioYearly.setImageResource(R.drawable.ic_check_circle)
            binding.ivRadioYearly.imageTintList = primaryCsl

            binding.cardPlanMonthly.setBackgroundResource(R.drawable.bg_plan_card_unselected)
            binding.ivRadioMonthly.setImageResource(R.drawable.bg_status_pill)
            binding.ivRadioMonthly.imageTintList = ContextCompat.getColorStateList(context, R.color.stroke_subtle)

            val yearlyPrice = entitlement.yearlyPrice
            if (yearlyPrice != null) {
                binding.btnUpgradePremium.text = getString(R.string.premium_unlock_annual_discount_fmt, discountPercent)
            } else {
                binding.btnUpgradePremium.text = getString(R.string.premium_unlock_annual)
            }
        } else {
            binding.cardPlanMonthly.background = com.fakegps.mocklocation.util.ThemeColorManager.createSelectedPlanCardDrawable(primaryColor, context)
            binding.ivRadioMonthly.setImageResource(R.drawable.ic_check_circle)
            binding.ivRadioMonthly.imageTintList = primaryCsl

            binding.cardPlanYearly.setBackgroundResource(R.drawable.bg_plan_card_unselected)
            binding.ivRadioYearly.setImageResource(R.drawable.bg_status_pill)
            binding.ivRadioYearly.imageTintList = ContextCompat.getColorStateList(context, R.color.stroke_subtle)

            val trialDesc = entitlement.freeTrialDescription
            if (entitlement.hasFreeTrial && !trialDesc.isNullOrBlank()) {
                binding.btnUpgradePremium.text = getString(R.string.premium_start_trial_fmt, trialDesc)
            } else if (entitlement.monthlyPrice != null) {
                binding.btnUpgradePremium.text = getString(R.string.premium_subscribe_monthly_fmt, entitlement.monthlyPrice)
            } else {
                binding.btnUpgradePremium.text = getString(R.string.premium_subscribe_monthly)
            }
        }
        binding.btnUpgradePremium.backgroundTintList = primaryCsl
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, context)
    }

    private fun setupListeners(billingManager: BillingManager) {
        binding.btnPremiumClose.setOnClickListener {
            dismiss()
        }

        binding.btnBillingHelp.setOnClickListener {
            showBillingDiagnosticsDialog(billingManager)
        }

        binding.btnUpgradePremium.setOnClickListener {
            activity?.let { act ->
                if (selectedPlan == PremiumEntitlement.PlanType.MONTHLY && !billingManager.hasMonthlyOffer()) {
                    if (billingManager.hasYearlyOffer()) {
                        showMonthlyOfferUnavailableDialog(billingManager)
                    } else {
                        showBillingDiagnosticsDialog(billingManager, getString(R.string.premium_products_unavailable))
                    }
                    return@setOnClickListener
                }

                val result = billingManager.launchPurchaseFlow(act, selectedPlan)
                if (result == null) {
                    binding.btnUpgradePremium.text = getString(R.string.premium_connecting_google_play)
                }
            }
        }



        binding.btnManageSubscription.setOnClickListener {
            activity?.let { act ->
                billingManager.openManageSubscriptions(act)
            }
        }

        binding.btnRestorePurchases.setOnClickListener {
            billingManager.refreshProductDetails()
            Toast.makeText(requireContext(), getString(R.string.premium_reconciling_purchases), Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeBillingState(billingManager: BillingManager) {
        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.entitlementState.collectLatest { entitlement ->
                renderEntitlement(entitlement, billingManager)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.isLoadingProducts.collectLatest { isLoading ->
                if (!isLoading) {
                    renderPlanSelectionUI()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.diagnosticEvent.collectLatest { event ->
                if (!event.isNullOrBlank()) {
                    renderPlanSelectionUI() // Always reset button text so it never stays stuck
                    showBillingDiagnosticsDialog(billingManager, event)
                    billingManager.clearDiagnosticEvent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.purchaseMessage.collectLatest { msg ->
                if (!msg.isNullOrBlank()) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    billingManager.clearPurchaseMessage()
                }
            }
        }
    }

    private fun showMonthlyOfferUnavailableDialog(billingManager: BillingManager) {
        val ctx = context ?: return
        val yearlyPrice = billingManager.getYearlyFormattedPrice() ?: getString(R.string.premium_plan_annual)
        val discount = PromotionManager.getYearlyDiscountPercent(ctx)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.monthly_plan_setup_title))
            .setMessage(
                "Google Play Store currently has only the Annual plan active on this device ($yearlyPrice).\n\n" +
                "To subscribe monthly, ensure a Monthly base plan (e.g., 'monthly' or 'p1m') or subscription ID ('nowhere_pro_monthly') is set to 'Active' in Google Play Console.\n\n" +
                "Would you like to unlock the Annual plan now (Save $discount%)?"
            )
            .setPositiveButton("Unlock Annual ($discount% OFF)") { _, _ ->
                selectedPlan = PremiumEntitlement.PlanType.YEARLY
                renderPlanSelectionUI()
                activity?.let { act ->
                    billingManager.launchPurchaseFlow(act, PremiumEntitlement.PlanType.YEARLY)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBillingDiagnosticsDialog(billingManager: BillingManager, promptReason: String? = null) {
        val ctx = context ?: return
        val diag = billingManager.getDiagnosticInfo()
        val isReady = diag.isReady
        val statusText = when {
            isReady && diag.productsCount > 0 -> "Connected (${diag.productsCount} products available)"
            isReady && diag.productsCount == 0 -> "Connected to Play Store, but 0 products returned"
            diag.billingStatus == BillingManager.BillingStatus.CONNECTING -> "Connecting in progress..."
            else -> "Disconnected / Error (${diag.billingStatus})"
        }

        val details = buildString {
            if (!promptReason.isNullOrBlank()) {
                append("Notice: ").append(promptReason).append("\n\n")
            }
            append("• Google Play Status: ").append(statusText).append("\n")
            append("• Monthly Plan: ").append(if (diag.hasMonthlyOffer) "Available (${diag.monthlyPrice ?: "Ready"})" else "Not active on Play Store").append("\n")
            append("• Annual Plan: ").append(if (diag.hasYearlyOffer) "Available (${diag.yearlyPrice ?: "Ready"})" else "Not active on Play Store").append("\n")
            if (diag.lastResponseCode != 0) {
                append("• Google Play Code: ").append(diag.lastResponseCode)
                if (!diag.lastDebugMessage.isNullOrBlank()) {
                    append(" (").append(diag.lastDebugMessage).append(")")
                }
                append("\n")
            }
            append("• Tested Product IDs: ").append(diag.testedCandidateIds.take(4).joinToString(", ")).append("...\n\n")

            append("Play Console Troubleshooting Checklist:\n")
            append("1. Subscriptions ➔ Base Plans: Ensure status is 'Active' (not Draft).\n")
            append("2. Settings ➔ License Testing: Add your device's Google account.\n")
            append("3. Device: Make sure Google Play Store is logged in.")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Google Play Connection Status")
            .setMessage(details)
            .setPositiveButton("Retry Connection") { _, _ ->
                billingManager.refreshProductDetails()
                Toast.makeText(ctx, "Reconnecting to Google Play...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun renderEntitlement(entitlement: PremiumEntitlement, billingManager: BillingManager) {
        val context = context ?: return

        // 1. VIP Discount Badge
        binding.tvYearlyDiscountBadge.text = PromotionManager.getYearlyDiscountBadgeText(context)

        // 2. Prices & Equivalents
        val monthlyPrice = entitlement.monthlyPrice ?: billingManager.getMonthlyFormattedPrice()
        val yearlyPrice = entitlement.yearlyPrice ?: billingManager.getYearlyFormattedPrice()
        val monthlyEquiv = entitlement.yearlyMonthlyEquivalent

        if (yearlyPrice != null) {
            binding.tvYearlyTotalPrice.text = yearlyPrice
            if (monthlyEquiv != null) {
                binding.tvYearlyMonthlyBreakdown.text = getString(R.string.premium_yearly_breakdown_fmt, monthlyEquiv)
            } else {
                binding.tvYearlyMonthlyBreakdown.text = getString(R.string.premium_yearly_value_fmt)
            }
        } else {
            binding.tvYearlyTotalPrice.text = getString(R.string.premium_plan_annual)
            binding.tvYearlyMonthlyBreakdown.text = getString(R.string.premium_yearly_discount_fmt)
        }

        if (monthlyPrice != null) {
            binding.tvMonthlyPrice.text = monthlyPrice
        } else {
            binding.tvMonthlyPrice.text = getString(R.string.premium_plan_monthly)
        }

        // 3. Free Trial Indicator
        val trialDesc = entitlement.freeTrialDescription
        if (entitlement.hasFreeTrial && !trialDesc.isNullOrBlank()) {
            binding.tvMonthlyTrialBadge.visibility = View.VISIBLE
            binding.tvMonthlyTrialBadge.text = trialDesc.uppercase()
            binding.tvMonthlySubtitle.text = getString(R.string.premium_free_trial_subtitle)
        } else {
            binding.tvMonthlyTrialBadge.visibility = View.GONE
            binding.tvMonthlySubtitle.text = getString(R.string.premium_monthly_flexible_subtitle)
        }

        // 4. Subscribed / Pending states
        if (entitlement.isPremium) {
            binding.layoutActivePremiumBanner.visibility = View.VISIBLE
            binding.layoutPendingBanner.visibility = View.GONE
            binding.btnUpgradePremium.visibility = View.GONE
            binding.btnManageSubscription.visibility = View.VISIBLE
        } else if (entitlement.isPending) {
            binding.layoutActivePremiumBanner.visibility = View.GONE
            binding.layoutPendingBanner.visibility = View.VISIBLE
            binding.btnUpgradePremium.visibility = View.GONE
            binding.btnManageSubscription.visibility = View.VISIBLE
        } else {
            binding.layoutActivePremiumBanner.visibility = View.GONE
            binding.layoutPendingBanner.visibility = View.GONE
            binding.btnManageSubscription.visibility = View.GONE
            binding.btnUpgradePremium.visibility = View.VISIBLE
            renderPlanSelectionUI()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

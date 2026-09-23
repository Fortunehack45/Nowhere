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
 * real-time localized pricing, Google Play Billing 7.x checkout, and subscription management.
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
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { dlg ->
            com.fakegps.mocklocation.util.FrostedGlassManager.applyWindowBlur(dlg, radiusDp = 35)
            try {
                dlg.window?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)
            } catch (ignored: Exception) {}
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

        binding.btnUpgradePremium.setOnClickListener {
            activity?.let { act ->
                billingManager.launchPurchaseFlow(act, selectedPlan)
            }
        }

        binding.btnManageSubscription.setOnClickListener {
            activity?.let { act ->
                billingManager.openManageSubscriptions(act)
            }
        }

        binding.btnRestorePurchases.setOnClickListener {
            billingManager.queryActivePurchases()
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
            billingManager.purchaseMessage.collectLatest { msg ->
                if (!msg.isNullOrBlank()) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    billingManager.clearPurchaseMessage()
                }
            }
        }
    }

    private fun renderEntitlement(entitlement: PremiumEntitlement, billingManager: BillingManager) {
        val context = context ?: return

        // 1. VIP Discount Badge
        binding.tvYearlyDiscountBadge.text = PromotionManager.getYearlyDiscountBadgeText(context)

        // 2. Prices & Equivalents
        val monthlyPrice = entitlement.monthlyPrice ?: billingManager.getFormattedPrice()
        val yearlyPrice = entitlement.yearlyPrice
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

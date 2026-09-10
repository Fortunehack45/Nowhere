package com.fakegps.mocklocation.ui.dialogs

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutDialogAppReviewBinding
import com.fakegps.mocklocation.util.AppReviewManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AppReviewBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAppReviewBinding? = null
    private val binding get() = _binding!!

    private var selectedRating: Int = 5
    private lateinit var starViews: List<ImageView>

    companion object {
        const val TAG = "AppReviewBottomSheet"

        fun newInstance(): AppReviewBottomSheet {
            return AppReviewBottomSheet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogAppReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        starViews = listOf(
            binding.ivStar1,
            binding.ivStar2,
            binding.ivStar3,
            binding.ivStar4,
            binding.ivStar5
        )

        updateStarDisplay(5)

        starViews.forEachIndexed { index, starView ->
            starView.setOnClickListener {
                val rating = index + 1
                selectedRating = rating
                updateStarDisplay(rating)

                if (rating >= 4) {
                    binding.btnRateOnPlayStore.text = "⭐ Rate $rating Stars on Google Play"
                } else {
                    binding.btnRateOnPlayStore.text = "⭐ Send Rating ($rating Stars)"
                }
            }
        }

        binding.btnRateOnPlayStore.setOnClickListener {
            context?.let { ctx ->
                AppReviewManager.openPlayStoreReview(ctx)
            }
            dismiss()
        }

        binding.btnRemindLater.setOnClickListener {
            context?.let { ctx ->
                AppReviewManager.onRemindLater(ctx)
            }
            dismiss()
        }

        binding.btnNeverAskAgain.setOnClickListener {
            context?.let { ctx ->
                AppReviewManager.onNeverAskAgain(ctx)
            }
            dismiss()
        }
    }

    private fun updateStarDisplay(rating: Int) {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.badge_warning_text)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.stroke_subtle)

        starViews.forEachIndexed { index, star ->
            if (index < rating) {
                star.imageTintList = ColorStateList.valueOf(activeColor)
                star.alpha = 1.0f
            } else {
                star.imageTintList = ColorStateList.valueOf(inactiveColor)
                star.alpha = 0.45f
            }
        }

        binding.tvRatingPrompt.text = when (rating) {
            5 -> "⭐⭐⭐⭐⭐ Outstanding!"
            4 -> "⭐⭐⭐⭐ Great!"
            3 -> "⭐⭐⭐ Good"
            2 -> "⭐⭐ Fair"
            else -> "⭐ Needs Improvement"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

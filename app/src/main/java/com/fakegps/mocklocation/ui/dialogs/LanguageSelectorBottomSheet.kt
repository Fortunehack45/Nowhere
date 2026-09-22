package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.ItemLanguageOptionBinding
import com.fakegps.mocklocation.databinding.LayoutDialogLanguageSelectorBinding
import com.fakegps.mocklocation.util.LocaleHelper
import com.fakegps.mocklocation.util.SupportedLanguage
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.content.ContextCompat

import com.fakegps.mocklocation.util.ThemeColorManager

class LanguageSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogLanguageSelectorBinding? = null
    private val binding get() = _binding!!

    var onLanguageChanged: (() -> Unit)? = null

    companion object {
        const val TAG = "LanguageSelectorBottomSheet"

        fun newInstance(): LanguageSelectorBottomSheet {
            return LanguageSelectorBottomSheet()
        }
    }

    override fun getTheme(): Int = R.style.Theme_Nowhere_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogLanguageSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLanguageClose.setOnClickListener {
            try {
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                // Ignore if already dismissed
            }
        }

        binding.ivLanguageHeaderIcon.imageTintList = ThemeColorManager.getPrimaryColorStateList(requireContext())
        ThemeColorManager.applyThemeRecursively(binding.root, requireContext())

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val currentCode = LocaleHelper.getSelectedLanguage(requireContext())
        val adapter = LanguageAdapter(
            languages = LocaleHelper.TOP_10_LANGUAGES,
            selectedCode = currentCode
        ) { selectedLanguage ->
            val appContext = context?.applicationContext
            try {
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                // Ignore if already dismissed or activity finished
            }
            if (selectedLanguage.code != currentCode && appContext != null) {
                LocaleHelper.applyLanguage(appContext, selectedLanguage.code)
                try {
                    Toast.makeText(
                        appContext,
                        "${selectedLanguage.flagEmoji} ${selectedLanguage.nativeName}",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (ignored: Exception) {}
                try {
                    onLanguageChanged?.invoke()
                } catch (ignored: Exception) {}
            }
        }

        binding.rvLanguageList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLanguageList.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class LanguageAdapter(
        private val languages: List<SupportedLanguage>,
        private val selectedCode: String,
        private val onSelect: (SupportedLanguage) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

        class LanguageViewHolder(val binding: ItemLanguageOptionBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
            val binding = ItemLanguageOptionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return LanguageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
            val item = languages[position]
            val isSelected = item.code == selectedCode
            val context = holder.itemView.context

            holder.binding.tvLanguageFlag.text = item.flagEmoji
            holder.binding.tvLanguageNativeName.text = item.nativeName
            holder.binding.tvLanguageEnglishName.text = item.englishName

            if (isSelected) {
                holder.binding.tvLanguageSelectedPill.visibility = View.VISIBLE
                holder.binding.tvLanguageSelectedPill.backgroundTintList =
                    ThemeColorManager.getLightTintStateList(context)
                holder.binding.tvLanguageSelectedPill.setTextColor(
                    ThemeColorManager.getPrimaryColor(context)
                )

                holder.binding.ivLanguageSelectedCheck.visibility = View.VISIBLE
                holder.binding.ivLanguageSelectedCheck.imageTintList =
                    ThemeColorManager.getPrimaryColorStateList(context)

                holder.binding.cardLanguageOption.strokeColor =
                    ThemeColorManager.getPrimaryColor(context)
                holder.binding.cardLanguageOption.setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        R.color.surface_card_elevated
                    )
                )
            } else {
                holder.binding.tvLanguageSelectedPill.visibility = View.GONE
                holder.binding.ivLanguageSelectedCheck.visibility = View.GONE
                holder.binding.cardLanguageOption.strokeColor = ContextCompat.getColor(
                    context,
                    R.color.stroke_subtle
                )
                holder.binding.cardLanguageOption.setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        R.color.surface_card
                    )
                )
            }

            holder.binding.cardLanguageOption.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount(): Int = languages.size
    }
}

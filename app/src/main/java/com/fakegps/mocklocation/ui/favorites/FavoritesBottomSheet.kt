package com.fakegps.mocklocation.ui.favorites

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.databinding.BottomSheetFavoritesBinding
import com.fakegps.mocklocation.ui.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class FavoritesBottomSheet @JvmOverloads constructor(
    private var onLocationSelected: ((FavoriteLocation) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "FavoritesBottomSheet"
    }

    private var _binding: BottomSheetFavoritesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: FavoritesAdapter
    private var allFavoritesList: List<FavoriteLocation> = emptyList()
    private var selectedTag: String = "All"
    private var currentSearchQuery: String = ""

    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                try {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        val text = BufferedReader(InputStreamReader(stream)).readText()
                        viewModel.importFavoritesJson(text) { count ->
                            if (count >= 0) {
                                Toast.makeText(requireContext(), "Imported $count favorites", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Failed to parse JSON", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearchAndFilter()
        setupExportImport()
        observeData()

        val ctx = requireContext()
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(ctx)
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, ctx)
        binding.btnImportJson.setTextColor(primaryColor)
        binding.btnExportJson.setTextColor(primaryColor)
    }

    private fun setupRecyclerView() {
        adapter = FavoritesAdapter(
            onItemClick = { location ->
                onLocationSelected?.invoke(location)
                dismiss()
            },
            onDeleteClick = { location ->
                viewModel.deleteFavorite(location)
            }
        )
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter
    }

    private fun setupSearchAndFilter() {
        binding.etSearchFavorites.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                filterList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupExportImport() {
        binding.btnExportJson.setOnClickListener {
            if (allFavoritesList.isEmpty()) {
                Toast.makeText(requireContext(), "No favorites to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val json = JsonBackupHelper.exportToJson(allFavoritesList)
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Favorites JSON Backup", json)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Favorites JSON copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnImportJson.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            importJsonLauncher.launch(intent)
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allFavorites.collectLatest { list ->
                allFavoritesList = list
                filterList()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allTags.collectLatest { tags ->
                populateTagChips(tags)
            }
        }
    }

    private fun populateTagChips(tags: List<String>) {
        binding.chipGroupTags.removeAllViews()
        val ctx = context ?: return
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(ctx)
        val surfaceDark = androidx.core.content.ContextCompat.getColor(ctx, R.color.surface_dark)
        val strokeSubtle = androidx.core.content.ContextCompat.getColor(ctx, R.color.stroke_subtle)
        val textSecondary = androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)

        fun styleChip(chip: Chip, isChecked: Boolean) {
            chip.isCheckable = true
            chip.isChecked = isChecked
            if (isChecked) {
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(primaryColor)
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(primaryColor)
                chip.setTextColor(android.graphics.Color.WHITE)
            } else {
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(surfaceDark)
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(strokeSubtle)
                chip.setTextColor(textSecondary)
            }
        }

        val allChip = Chip(ctx).apply {
            text = "All"
            styleChip(this, selectedTag == "All")
            setOnClickListener {
                selectedTag = "All"
                populateTagChips(tags)
                filterList()
            }
        }
        binding.chipGroupTags.addView(allChip)

        for (tag in tags) {
            val chip = Chip(ctx).apply {
                text = tag
                styleChip(this, selectedTag.equals(tag, ignoreCase = true))
                setOnClickListener {
                    selectedTag = tag
                    populateTagChips(tags)
                    filterList()
                }
            }
            binding.chipGroupTags.addView(chip)
        }
    }

    private fun filterList() {
        var filtered = allFavoritesList

        if (selectedTag != "All") {
            filtered = filtered.filter { it.tag.equals(selectedTag, ignoreCase = true) }
        }

        if (currentSearchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.name.contains(currentSearchQuery, ignoreCase = true) ||
                        it.tag.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)
        binding.tvEmptyFavorites.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fakegps.mocklocation.databinding.LayoutDialogAppUpdateBinding
import com.fakegps.mocklocation.util.AppUpdateManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AppUpdateBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAppUpdateBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "AppUpdateBottomSheet"
        private const val ARG_VERSION = "arg_version"

        fun newInstance(version: String = ""): AppUpdateBottomSheet {
            return AppUpdateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VERSION, version)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogAppUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val version = arguments?.getString(ARG_VERSION)?.takeIf { it.isNotBlank() } ?: "New Version"
        binding.tvUpdateVersionBadge.text = "$version is available on Google Play"
        binding.tvReleaseTitle.text = "Official Google Play Update"
        binding.tvReleaseChangelog.text = "• Enhanced location simulation accuracy\n• Speed telemetry and engine stability updates\n• User interface and performance optimizations"

        binding.btnDownloadUpdate.text = "Update on Google Play"
        binding.btnDownloadUpdate.setOnClickListener {
            context?.let { c -> AppUpdateManager.openPlayStore(c) }
            dismiss()
        }

        binding.btnRemindLater.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

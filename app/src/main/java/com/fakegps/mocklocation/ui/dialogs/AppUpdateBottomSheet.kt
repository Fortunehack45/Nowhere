package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fakegps.mocklocation.R
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

        val version = arguments?.getString(ARG_VERSION)?.takeIf { it.isNotBlank() } ?: getString(R.string.app_update_new_version_available)
        binding.tvUpdateVersionBadge.text = version
        binding.tvReleaseTitle.text = getString(R.string.app_update_whats_new_in_this_release)
        binding.tvReleaseChangelog.text = getString(R.string.app_update_enhanced_location_simulation_accuracyn_speed)

        binding.btnDownloadUpdate.text = getString(R.string.app_update_update_on_google_play)
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

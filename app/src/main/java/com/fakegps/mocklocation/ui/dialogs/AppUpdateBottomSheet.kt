package com.fakegps.mocklocation.ui.dialogs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutDialogAppUpdateBinding
import com.fakegps.mocklocation.util.AppUpdateManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AppUpdateBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAppUpdateBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "AppUpdateBottomSheet"
        private const val ARG_LATEST_VERSION = "arg_latest_version"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_CHANGELOG = "arg_changelog"
        private const val ARG_DOWNLOAD_URL = "arg_download_url"
        private const val ARG_HTML_URL = "arg_html_url"

        fun newInstance(updateInfo: AppUpdateManager.UpdateInfo): AppUpdateBottomSheet {
            return AppUpdateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_LATEST_VERSION, updateInfo.latestVersion)
                    putString(ARG_TITLE, updateInfo.releaseTitle)
                    putString(ARG_CHANGELOG, updateInfo.releaseNotes)
                    putString(ARG_DOWNLOAD_URL, updateInfo.downloadUrl)
                    putString(ARG_HTML_URL, updateInfo.htmlUrl)
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

        val latestVersion = arguments?.getString(ARG_LATEST_VERSION) ?: "1.1.0"
        val title = arguments?.getString(ARG_TITLE)?.takeIf { it.isNotBlank() } ?: "Nowhere Performance & Features Update"
        val rawChangelog = arguments?.getString(ARG_CHANGELOG) ?: ""

        val cleanedChangelog = if (rawChangelog.isBlank() || rawChangelog.contains("null", ignoreCase = true)) {
            "• Enhanced location simulation accuracy\n• Speed telemetry and engine stability updates\n• User interface and performance optimizations"
        } else {
            rawChangelog.trim()
        }

        binding.tvUpdateVersionBadge.text = "v$latestVersion is ready to install"
        binding.tvReleaseTitle.text = title
        binding.tvReleaseChangelog.text = cleanedChangelog

        binding.btnDownloadUpdate.setOnClickListener {
            openPlayStoreForUpdate()
        }

        binding.btnRemindLater.setOnClickListener {
            context?.let { c ->
                AppUpdateManager.dismissVersion(c, latestVersion)
            }
            dismiss()
        }
    }

    private fun openPlayStoreForUpdate() {
        val context = context ?: return
        val packageName = context.packageName

        try {
            // Intent to launch Google Play Store App
            val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(playStoreIntent)
            dismiss()
        } catch (e: ActivityNotFoundException) {
            // Fallback to browser Play Store link
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
                dismiss()
            } catch (err: Exception) {
                Toast.makeText(context, "Could not open Google Play Store.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


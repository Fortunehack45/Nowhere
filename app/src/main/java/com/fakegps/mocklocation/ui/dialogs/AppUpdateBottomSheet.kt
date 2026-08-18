package com.fakegps.mocklocation.ui.dialogs

import android.content.Intent
import android.net.Uri
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

        val latestVersion = arguments?.getString(ARG_LATEST_VERSION) ?: "1.0.0"
        val title = arguments?.getString(ARG_TITLE) ?: "Nowhere Update"
        val changelog = arguments?.getString(ARG_CHANGELOG) ?: "New features and bug fixes."
        val downloadUrl = arguments?.getString(ARG_DOWNLOAD_URL) ?: ""
        val htmlUrl = arguments?.getString(ARG_HTML_URL) ?: "https://github.com/Fortunehack45/Nowhere/releases"

        binding.tvUpdateVersionBadge.text = "v$latestVersion is ready to install"
        binding.tvReleaseTitle.text = title
        binding.tvReleaseChangelog.text = changelog

        binding.btnDownloadUpdate.setOnClickListener {
            val targetUrl = downloadUrl.ifEmpty { htmlUrl }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            dismiss()
        }

        binding.btnRemindLater.setOnClickListener {
            context?.let { ctx ->
                AppUpdateManager.dismissVersion(ctx, latestVersion)
            }
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

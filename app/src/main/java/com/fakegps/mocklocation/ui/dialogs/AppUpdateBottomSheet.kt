package com.fakegps.mocklocation.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutDialogAppUpdateBinding
import com.fakegps.mocklocation.util.AppUpdateManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAppUpdateBinding? = null
    private val binding get() = _binding!!

    private var isDownloading = false
    private var downloadedApkFile: File? = null

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

        val ctx = requireContext()
        val existingApk = AppUpdateManager.getDownloadedApkFile(ctx, latestVersion)
        if (existingApk != null) {
            downloadedApkFile = existingApk
            binding.btnDownloadUpdate.text = "Install Now"
            binding.btnDownloadUpdate.setIconResource(R.drawable.ic_check_circle)
        }

        binding.btnDownloadUpdate.setOnClickListener {
            val file = downloadedApkFile
            if (file != null && file.exists()) {
                val installed = AppUpdateManager.installApk(requireContext(), file)
                if (installed) {
                    dismiss()
                }
                return@setOnClickListener
            }

            if (isDownloading) return@setOnClickListener

            if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                startInAppDownload(
                    AppUpdateManager.UpdateInfo(
                        isUpdateAvailable = true,
                        currentVersion = com.fakegps.mocklocation.BuildConfig.VERSION_NAME,
                        latestVersion = latestVersion,
                        releaseTitle = title,
                        releaseNotes = changelog,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl
                    )
                )
            } else {
                openInBrowser(downloadUrl.ifEmpty { htmlUrl })
            }
        }

        binding.btnRemindLater.setOnClickListener {
            if (!isDownloading) {
                context?.let { c ->
                    AppUpdateManager.dismissVersion(c, latestVersion)
                }
                dismiss()
            } else {
                dismiss()
            }
        }
    }

    private fun startInAppDownload(updateInfo: AppUpdateManager.UpdateInfo) {
        val ctx = context ?: return
        isDownloading = true
        isCancelable = false

        binding.cardDownloadProgress.visibility = View.VISIBLE
        binding.progressBarUpdate.progress = 0
        binding.progressBarUpdate.isIndeterminate = false
        binding.tvDownloadPercentage.text = "0%"
        binding.tvDownloadStatus.text = "Downloading APK..."
        binding.tvDownloadBytes.text = "Starting download..."

        binding.btnDownloadUpdate.text = "Downloading..."
        binding.btnDownloadUpdate.isEnabled = false
        binding.btnRemindLater.text = "Hide"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = AppUpdateManager.downloadApk(ctx.applicationContext, updateInfo) { percent, downloadedBytes, totalBytes ->
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.progressBarUpdate.progress = percent
                    binding.tvDownloadPercentage.text = "$percent%"

                    val downloadedMb = String.format("%.1f", downloadedBytes.toDouble() / (1024 * 1024))
                    if (totalBytes > 0) {
                        val totalMb = String.format("%.1f", totalBytes.toDouble() / (1024 * 1024))
                        binding.tvDownloadBytes.text = "$downloadedMb MB / $totalMb MB"
                    } else {
                        binding.tvDownloadBytes.text = "$downloadedMb MB downloaded"
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isDownloading = false
                isCancelable = true
                if (_binding == null) return@withContext

                result.fold(
                    onSuccess = { apkFile ->
                        downloadedApkFile = apkFile
                        binding.tvDownloadStatus.text = "Download Complete!"
                        binding.progressBarUpdate.progress = 100
                        binding.btnDownloadUpdate.isEnabled = true
                        binding.btnDownloadUpdate.text = "Install Now"
                        binding.btnDownloadUpdate.setIconResource(R.drawable.ic_check_circle)
                        binding.btnRemindLater.text = "Later"

                        // Trigger install immediately
                        val installed = AppUpdateManager.installApk(requireContext(), apkFile)
                        if (installed) {
                            dismiss()
                        }
                    },
                    onFailure = { error ->
                        binding.cardDownloadProgress.visibility = View.GONE
                        binding.btnDownloadUpdate.isEnabled = true
                        binding.btnDownloadUpdate.text = "Open in Browser"
                        binding.btnRemindLater.text = "Later"
                        Toast.makeText(requireContext(), "Direct download failed: ${error.message}", Toast.LENGTH_SHORT).show()

                        binding.btnDownloadUpdate.setOnClickListener {
                            openInBrowser(updateInfo.htmlUrl)
                        }
                    }
                )
            }
        }
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            dismiss()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

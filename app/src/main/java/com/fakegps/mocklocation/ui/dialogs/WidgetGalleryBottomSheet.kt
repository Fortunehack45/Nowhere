package com.fakegps.mocklocation.ui.dialogs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.ItemWidgetPreviewBinding
import com.fakegps.mocklocation.databinding.LayoutDialogWidgetGalleryBinding
import com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereRouteWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereSearchWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereWeatherWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereSessionTimerWidgetProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

data class WidgetPreviewItem(
    val title: String,
    val sizeLabel: String,
    val description: String,
    val layoutResId: Int,
    val providerClass: Class<*>
)

class WidgetGalleryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogWidgetGalleryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogWidgetGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val widgetList = listOf(
            WidgetPreviewItem(
                title = "Nowhere Master Control Hub",
                sizeLabel = "4 x 2",
                description = "Primary control station: 1-tap teleport, live location display, mode switcher, and instant simulation stop.",
                layoutResId = R.layout.widget_nowhere_layout,
                providerClass = NowhereAppWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Live Route Telemetry & Progress",
                sizeLabel = "4 x 2",
                description = "Shows departure, destination, real-time distance covered, distance remaining, and progress bar with pause/stop controls.",
                layoutResId = R.layout.widget_nowhere_route_layout,
                providerClass = NowhereRouteWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "IP Privacy Shield",
                sizeLabel = "4 x 2",
                description = "Instant 1-tap VPN masking toggle, displaying virtual IP address, active country node, and server latency.",
                layoutResId = R.layout.widget_nowhere_vpn_layout,
                providerClass = NowhereVpnWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Game Booster FastPath & QoS",
                sizeLabel = "4 x 2",
                description = "1-Tap gaming route optimization with real-time ping (14ms), Google BBR FastPath, and traffic telemetry.",
                layoutResId = R.layout.widget_nowhere_game_boost_layout,
                providerClass = com.fakegps.mocklocation.ui.widget.NowhereGameBoostWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Location Weather Radar",
                sizeLabel = "4 x 2",
                description = "Live temperature, weather condition icon, humidity, wind, and forecast for your mock GPS coordinates.",
                layoutResId = R.layout.widget_nowhere_weather_layout,
                providerClass = NowhereWeatherWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Quick Search & Teleport Launcher",
                sizeLabel = "4 x 2",
                description = "Search worldwide cities or direct coordinates and instantly jump without opening the full application.",
                layoutResId = R.layout.widget_nowhere_search_layout,
                providerClass = NowhereSearchWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Favorite Bookmarks & Hotspots",
                sizeLabel = "4 x 2",
                description = "Quick-access list of your saved favorite destinations for rapid one-tap teleportation.",
                layoutResId = R.layout.widget_nowhere_favorites_layout,
                providerClass = NowhereFavoritesWidgetProvider::class.java
            ),
            WidgetPreviewItem(
                title = "Session Duration & Countdown",
                sizeLabel = "4 x 2",
                description = "Live simulation countdown clock (HH:MM:SS), connection status, and 1-tap +2 Hours extension trigger.",
                layoutResId = R.layout.widget_nowhere_session_timer_layout,
                providerClass = NowhereSessionTimerWidgetProvider::class.java
            )
        )

        binding.rvWidgetPreviews.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWidgetPreviews.adapter = WidgetPreviewAdapter(requireContext(), widgetList) { item ->
            requestPinWidget(item)
        }
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(requireContext())
        val darkColor = com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(requireContext())
        binding.ivWidgetGalleryLogo.setImageDrawable(com.fakegps.mocklocation.util.ThemeColorManager.getThemedLogoDrawable(requireContext(), primaryColor, darkColor))
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())
    }

    private fun requestPinWidget(item: WidgetPreviewItem) {
        val context = requireContext()
        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val provider = ComponentName(context, item.providerClass)
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    item.providerClass.hashCode(),
                    Intent(context, item.providerClass),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                appWidgetManager.requestPinAppWidget(provider, null, successCallback)
                Toast.makeText(context, "Adding \"${item.title}\" to Home Screen...", Toast.LENGTH_SHORT).show()
                return
            }
        }

        Toast.makeText(
            context,
            "To add \"${item.title}\", long-press your Home Screen and select Widgets -> Nowhere",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class WidgetPreviewAdapter(
        private val context: Context,
        private val items: List<WidgetPreviewItem>,
        private val onAddClick: (WidgetPreviewItem) -> Unit
    ) : RecyclerView.Adapter<WidgetPreviewAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemWidgetPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemWidgetPreviewBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: WidgetPreviewItem) {
                binding.tvWidgetTitle.text = item.title
                binding.tvWidgetSize.text = item.sizeLabel
                binding.tvWidgetDescription.text = item.description

                // Inflate the actual widget preview layout inside previewContainer
                binding.previewContainer.removeAllViews()
                try {
                    val previewView = LayoutInflater.from(context).inflate(item.layoutResId, binding.previewContainer, false)
                    val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivWidgetTeleportBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivWidgetRoutePlayPauseBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivWidgetGameBoostToggleBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivWidgetVpnToggleBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivWidgetWeatherDetailsBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.ImageView>(R.id.ivSearchWidgetTeleportBg)?.setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    previewView.findViewById<android.widget.TextView>(R.id.btnWidgetJoystick)?.setTextColor(primaryColor)
                    previewView.findViewById<android.widget.TextView>(R.id.btnWidgetRouteStop)?.setTextColor(primaryColor)
                    com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(previewView, context)
                    binding.previewContainer.addView(previewView)
                } catch (ignored: Exception) {}

                com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, context)
                binding.btnAddToHomeScreen.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(context)
                binding.tvWidgetSize.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context))

                binding.btnAddToHomeScreen.setOnClickListener {
                    onAddClick(item)
                }
            }
        }
    }
}

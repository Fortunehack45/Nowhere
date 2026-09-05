package com.fakegps.mocklocation.automation.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.fakegps.mocklocation.data.db.AppDatabase
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class WifiTriggerHandler(private val context: Context) {

    companion object {
        const val TRIGGER_ON_CONNECT = "ON_CONNECT"
        const val TRIGGER_ON_DISCONNECT = "ON_DISCONNECT"
        private const val DEBOUNCE_INTERVAL_MS = 10_000L // 10s debounce
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var fallbackReceiver: BroadcastReceiver? = null

    private val isRunning = AtomicBoolean(false)
    private var lastTriggerTime = 0L
    private var lastConnectedSsid: String? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // 1. ConnectivityManager.NetworkCallback for TRANSPORT_WIFI
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager?.getNetworkCapabilities(network)
                    val ssid = resolveSsid(caps)
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                        handleWifiStateChange(ssid, TRIGGER_ON_CONNECT)
                    }
                }

                override fun onLost(network: Network) {
                    val disconnectedSsid = lastConnectedSsid
                    if (!disconnectedSsid.isNullOrEmpty() && disconnectedSsid != "<unknown ssid>") {
                        handleWifiStateChange(disconnectedSsid, TRIGGER_ON_DISCONNECT)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val ssid = resolveSsid(networkCapabilities)
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>" && ssid != lastConnectedSsid) {
                        handleWifiStateChange(ssid, TRIGGER_ON_CONNECT)
                    }
                }
            }

            networkCallback?.let { callback ->
                connectivityManager?.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            // NetworkCallback registration fallback
        }

        // 2. Fallback BroadcastReceiver for OEMs throttling background callbacks
        try {
            fallbackReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val info = wifiManager?.connectionInfo
                        val ssid = sanitizeSsid(info?.ssid)
                        if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                            if (ssid != lastConnectedSsid) {
                                handleWifiStateChange(ssid, TRIGGER_ON_CONNECT)
                            }
                        } else if (lastConnectedSsid != null) {
                            handleWifiStateChange(lastConnectedSsid!!, TRIGGER_ON_DISCONNECT)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    fallbackReceiver,
                    IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(fallbackReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
            }
        } catch (e: Exception) {
            // Receiver registration fallback
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (ignored: Exception) {}
            networkCallback = null
        }

        fallbackReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (ignored: Exception) {}
            fallbackReceiver = null
        }

        scope.cancel()
    }

    private fun handleWifiStateChange(ssid: String, triggerType: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEBOUNCE_INTERVAL_MS) {
            return // Debounce flap
        }
        lastTriggerTime = now
        if (triggerType == TRIGGER_ON_CONNECT) {
            lastConnectedSsid = ssid
        } else if (triggerType == TRIGGER_ON_DISCONNECT && lastConnectedSsid == ssid) {
            lastConnectedSsid = null
        }

        scope.launch {
            val db = AppDatabase.getInstance(context)
            val settings = db.automationSettingsDao().getSettings()
            if (settings == null || !settings.wifiTriggersEnabled) {
                return@launch
            }

            val matchingTriggers = db.wifiTriggerDao().getEnabledTriggersForSsid(ssid, triggerType)
            for (trigger in matchingTriggers) {
                AutomationTargetResolver.resolveAndDispatch(
                    context,
                    trigger.targetType,
                    trigger.targetId,
                    "WiFi Geofence: '$ssid' ($triggerType)"
                )
            }
        }
    }

    private fun resolveSsid(caps: NetworkCapabilities?): String? {
        if (caps == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            if (wifiInfo != null) {
                return sanitizeSsid(wifiInfo.ssid)
            }
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return sanitizeSsid(wifiManager?.connectionInfo?.ssid)
    }

    private fun sanitizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) return null
        return rawSsid.trim().removeSurrounding("\"")
    }
}

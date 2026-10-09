package com.fakegps.mocklocation.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Standard background stub service for Emergency Kill Switch.
 * Completely decoupling from VpnService to comply with Google Play Developer Policy.
 */
class KillSwitchSinkholeService : Service() {

    companion object {
        const val ACTION_BYPASS = "com.fakegps.mocklocation.ACTION_KILL_SWITCH_BYPASS"

        var isSinkholeActive: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}

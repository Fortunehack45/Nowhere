package com.fakegps.mocklocation.automation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_wifi_triggers")
data class WifiTriggerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ssid: String,
    val triggerType: String, // ON_CONNECT, ON_DISCONNECT
    val targetType: String, // SINGLE_LOCATION, ROUTE
    val targetId: Long,
    val enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

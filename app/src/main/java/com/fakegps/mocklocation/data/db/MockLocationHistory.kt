package com.fakegps.mocklocation.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mock_location_history")
data class MockLocationHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val locationName: String = "",
    val mode: String = "TELEPORT",
    val timestamp: Long = System.currentTimeMillis()
)

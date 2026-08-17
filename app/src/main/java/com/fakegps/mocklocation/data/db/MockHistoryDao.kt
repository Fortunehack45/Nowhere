package com.fakegps.mocklocation.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MockHistoryDao {

    @Query("SELECT * FROM mock_location_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllLocationHistoryFlow(): Flow<List<MockLocationHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationHistory(item: MockLocationHistory): Long

    @Query("DELETE FROM mock_location_history WHERE id = :id")
    suspend fun deleteLocationHistoryById(id: Long)

    @Query("DELETE FROM mock_location_history")
    suspend fun clearAllLocationHistory()

    @Query("SELECT * FROM mock_route_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllRouteHistoryFlow(): Flow<List<MockRouteHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteHistory(item: MockRouteHistory): Long

    @Query("DELETE FROM mock_route_history WHERE id = :id")
    suspend fun deleteRouteHistoryById(id: Long)

    @Query("DELETE FROM mock_route_history")
    suspend fun clearAllRouteHistory()
}

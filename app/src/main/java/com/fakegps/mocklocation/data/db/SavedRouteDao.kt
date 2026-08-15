package com.fakegps.mocklocation.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {

    @Query("SELECT * FROM saved_routes ORDER BY createdAt DESC")
    fun getAllSavedRoutesFlow(): Flow<List<SavedRoute>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: SavedRoute): Long

    @Delete
    suspend fun deleteRoute(route: SavedRoute)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_routes WHERE id = :id LIMIT 1")
    suspend fun getRouteById(id: Long): SavedRoute?
}

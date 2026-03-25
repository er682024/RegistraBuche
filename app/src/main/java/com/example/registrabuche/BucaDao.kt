package com.example.registrabuche

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BucaDao {
    @Query("SELECT * FROM buche WHERE latitude = :lat AND longitude = :lon LIMIT 1")
    suspend fun getBuca(lat: Double, lon: Double): Buca?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(buca: Buca): Long

    @Query("UPDATE buche SET timestamp_last = :lastTimestamp, timestamp_resolved = NULL WHERE latitude = :lat AND longitude = :lon")
    suspend fun updateLastTimestamp(lat: Double, lon: Double, lastTimestamp: Long)

    @Query("UPDATE buche SET timestamp_resolved = :resolvedTimestamp WHERE latitude = :lat AND longitude = :lon")
    suspend fun markAsResolved(lat: Double, lon: Double, resolvedTimestamp: Long?)

    @Transaction
    suspend fun saveOrUpdate(lat: Double, lon: Double) {
        val roundedLat = Math.round(lat * 10000).toDouble() / 10000
        val roundedLon = Math.round(lon * 10000).toDouble() / 10000
        val currentTime = System.currentTimeMillis()
        
        val existing = getBuca(roundedLat, roundedLon)
        if (existing == null) {
            insert(Buca(roundedLat, roundedLon, currentTime, currentTime))
        } else {
            updateLastTimestamp(roundedLat, roundedLon, currentTime)
        }
    }

    @Query("SELECT * FROM buche WHERE timestamp_resolved IS NULL")
    suspend fun getAllActive(): List<Buca>

    @Query("SELECT * FROM buche")
    fun getAllFlow(): Flow<List<Buca>>

    @Query("SELECT * FROM buche")
    suspend fun getAll(): List<Buca>
}
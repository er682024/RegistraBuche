package com.example.registrabuche

import androidx.room.Entity

@Entity(tableName = "buche", primaryKeys = ["latitude", "longitude"])
data class Buca(
    val latitude: Double,
    val longitude: Double,
    val timestamp_insert: Long,
    val timestamp_last: Long,
    val timestamp_resolved: Long? = null
)
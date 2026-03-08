package com.example.dailytasks

data class LocationMessage(
    val id: String = "",
    val text: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val createdAtEpochMs: Long = 0L,
    val authorId: String = ""
)

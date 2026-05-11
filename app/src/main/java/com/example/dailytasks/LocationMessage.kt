package com.example.dailytasks

data class LocationMessage(
    val id: String = "",
    val text: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val createdAtEpochMs: Long = 0L,
    val authorId: String = "",
    /** Display name copied from the author's profile when the message was posted. */
    val authorUsername: String = "",
    val upvotes: Long = 0L,
    val downvotes: Long = 0L,
    val rating: Long = 0L
) {
    val displayedPoints: Long
        get() = rating
}

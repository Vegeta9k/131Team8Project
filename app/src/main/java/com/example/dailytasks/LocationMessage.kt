package com.example.dailytasks

// Represents one message pin stored in Firestore and rendered on the map/list UI.
data class LocationMessage(
    val id: String = "",
    val text: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val createdAtEpochMs: Long = 0L,
    val authorId: String = "",
    // Public name saved when the message was posted.
    val authorUsername: String = "",
    val upvotes: Long = 0L,
    val downvotes: Long = 0L,
    val rating: Long = 0L
) {
    // The UI shows rating as the message's visible score.
    val displayedPoints: Long
        get() = rating
}

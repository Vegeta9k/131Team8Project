package com.example.dailytasks

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class MessageSyncRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val messagesCollection = firestore.collection(MESSAGES_COLLECTION)

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous authentication failed.")
    }

    suspend fun addMessage(text: String, latitude: Double, longitude: Double): Result<Unit> {
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        val payload = mapOf(
            "text" to text,
            "latitude" to latitude,
            "longitude" to longitude,
            "createdAtEpochMs" to System.currentTimeMillis(),
            "authorId" to uid
        )
        return runCatching {
            messagesCollection.add(payload).await()
        }.map { Unit }
    }

    suspend fun deleteMessage(messageId: String): Result<Unit> {
        if (messageId.isBlank()) return Result.failure(IllegalArgumentException("Invalid message ID."))
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        return runCatching {
            val doc = messagesCollection.document(messageId).get().await()
            val authorId = doc.getString("authorId").orEmpty()
            if (authorId != uid) {
                error("You can delete only your own messages.")
            }
            messagesCollection.document(messageId).delete().await()
        }.map { Unit }
    }

    fun observeMessages(): Flow<Result<List<LocationMessage>>> = callbackFlow {
        val listener = messagesCollection
            .orderBy("createdAtEpochMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(Result.failure(error))
                    return@addSnapshotListener
                }

                val currentMessages = snapshot?.documents?.map { document ->
                    LocationMessage(
                        id = document.id,
                        text = document.getString("text").orEmpty(),
                        latitude = document.getDouble("latitude") ?: 0.0,
                        longitude = document.getDouble("longitude") ?: 0.0,
                        createdAtEpochMs = document.getLong("createdAtEpochMs") ?: 0L,
                        authorId = document.getString("authorId").orEmpty()
                    )
                }.orEmpty()

                trySend(Result.success(currentMessages))
            }

        awaitClose { listener.remove() }
    }

    companion object {
        private const val MESSAGES_COLLECTION = "messages"
    }
}

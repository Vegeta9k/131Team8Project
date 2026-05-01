package com.example.dailytasks

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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

    fun isGuestUser(): Boolean = auth.currentUser?.isAnonymous == true

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous authentication failed.")
    }

    suspend fun registerWithEmailPassword(email: String, password: String): Result<String> {
        val trimmed = email.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Enter an email address."))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        if (password.length < MIN_PASSWORD_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Password must be at least $MIN_PASSWORD_LENGTH characters.")
            )
        }
        return runCatching {
            val result = auth.createUserWithEmailAndPassword(trimmed, password).await()
            result.user?.uid ?: error("Registration failed.")
        }.mapError(::toFriendlyAuthError)
    }

    suspend fun signInWithEmailPassword(email: String, password: String): Result<String> {
        val trimmed = email.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Enter an email address."))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        return runCatching {
            val result = auth.signInWithEmailAndPassword(trimmed, password).await()
            result.user?.uid ?: error("Sign-in failed.")
        }.mapError(::toFriendlyAuthError)
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun addMessage(text: String, latitude: Double, longitude: Double): Result<Unit> {
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        val payload = mapOf(
            "text" to text,
            "latitude" to latitude,
            "longitude" to longitude,
            "createdAtEpochMs" to System.currentTimeMillis(),
            "authorId" to uid,
            "upvotes" to 0L,
            "downvotes" to 0L,
            "rating" to 0L
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

    suspend fun voteOnMessage(messageId: String, isUpvote: Boolean): Result<Unit> {
        if (messageId.isBlank()) return Result.failure(IllegalArgumentException("Invalid message ID."))
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        return runCatching {
            firestore.runTransaction { transaction ->
                val docRef = messagesCollection.document(messageId)
                val voteRef = docRef.collection(VOTES_COLLECTION).document(uid)
                val snapshot = transaction.get(docRef)
                val voteSnapshot = transaction.get(voteRef)
                if (!snapshot.exists()) {
                    return@runTransaction null
                }

                val currentUpvotes = snapshot.getLong("upvotes") ?: 0L
                val currentDownvotes = snapshot.getLong("downvotes") ?: 0L
                val currentRating = snapshot.getLong("rating") ?: (currentUpvotes - currentDownvotes)
                val previousVote = voteSnapshot.getLong("vote") ?: 0L
                val newVote = if (isUpvote) 1L else -1L

                if (previousVote == newVote) {
                    return@runTransaction null
                }

                val upvoteDelta = when {
                    previousVote == 1L -> -1L
                    newVote == 1L -> 1L
                    else -> 0L
                }
                val downvoteDelta = when {
                    previousVote == -1L -> -1L
                    newVote == -1L -> 1L
                    else -> 0L
                }
                val ratingDelta = newVote - previousVote
                val updatedRating = currentRating + ratingDelta

                if (updatedRating <= AUTO_DELETE_RATING_THRESHOLD) {
                    transaction.delete(docRef)
                } else {
                    transaction.set(voteRef, mapOf("vote" to newVote))
                    transaction.update(
                        docRef,
                        mapOf(
                            "upvotes" to currentUpvotes + upvoteDelta,
                            "downvotes" to currentDownvotes + downvoteDelta,
                            "rating" to updatedRating
                        )
                    )
                }
            }.await()
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
                        authorId = document.getString("authorId").orEmpty(),
                        upvotes = document.getLong("upvotes") ?: 0L,
                        downvotes = document.getLong("downvotes") ?: 0L,
                        rating = document.getLong("rating") ?: 0L
                    )
                }.orEmpty()

                trySend(Result.success(currentMessages))
            }

        awaitClose { listener.remove() }
    }

    companion object {
        private const val MESSAGES_COLLECTION = "messages"
        private const val VOTES_COLLECTION = "votes"
        private const val AUTO_DELETE_RATING_THRESHOLD = -2L
        private const val MIN_PASSWORD_LENGTH = 6
    }

    private fun toFriendlyAuthError(throwable: Throwable): Throwable {
        val authErrorCode = (throwable as? FirebaseAuthException)?.errorCode
        return when (authErrorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" -> IllegalStateException(
                "Email/password sign-in is not enabled in Firebase. Turn on Authentication > Sign-in method > Email/Password."
            )
            "ERROR_INVALID_EMAIL" -> IllegalArgumentException("Enter a valid email address.")
            "ERROR_EMAIL_ALREADY_IN_USE" -> IllegalStateException("That email is already registered. Try logging in instead.")
            "ERROR_WEAK_PASSWORD" -> IllegalArgumentException(
                "Password must be at least $MIN_PASSWORD_LENGTH characters."
            )
            "ERROR_USER_NOT_FOUND",
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL" -> IllegalArgumentException("Incorrect email or password.")
            else -> throwable
        }
    }

    private inline fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> {
        return fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(transform(it)) }
        )
    }
}

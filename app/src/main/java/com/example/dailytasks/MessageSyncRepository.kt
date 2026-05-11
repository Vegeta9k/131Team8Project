package com.example.dailytasks

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.mail.*
import javax.mail.internet.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

class MessageSyncRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val messagesCollection = firestore.collection(MESSAGES_COLLECTION)
    private val usersCollection = firestore.collection(USERS_COLLECTION)

    fun currentUserId(): String? = auth.currentUser?.uid
    fun currentUserEmail(): String? = auth.currentUser?.email

    fun isSignedIn(): Boolean = auth.currentUser != null

    fun isGuestUser(): Boolean = auth.currentUser?.isAnonymous == true

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous authentication failed.")
    }

    suspend fun fetchUsernameForUid(uid: String): String? {
        if (uid.isBlank()) return null
        return runCatching {
            val doc = usersCollection.document(uid).get().await()
            if (!doc.exists()) return@runCatching null
            doc.getString("username")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun refreshCurrentUsername(): String? {
        val uid = currentUserId() ?: return null
        if (isGuestUser()) return null
        return fetchUsernameForUid(uid)
    }

    suspend fun registerWithEmailPassword(
        email: String,
        password: String,
        username: String
    ): Result<String> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) return Result.failure(IllegalArgumentException("Enter an email address."))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        if (!isPasswordValid(password)) {
            return Result.failure(
                IllegalArgumentException("Password must be at least $MIN_PASSWORD_LENGTH characters and include uppercase, lowercase, number, and special character.")
            )
        }
        val normalizedUsername = runCatching { normalizeAndValidateUsername(username) }
            .getOrElse { return Result.failure(it) }
        return runCatching {
            checkUsernameStillAvailable(normalizedUsername).getOrElse { throw it }

            val result = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val uid = result.user?.uid ?: error("Registration failed.")

            val profile = mapOf(
                "username" to normalizedUsername,
                "usernameLower" to normalizedUsername.lowercase()
            )
            usersCollection.document(uid).set(profile).await()
            uid
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

    suspend fun updatePassword(newPassword: String): Result<Unit> {
        if (newPassword.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        return runCatching {
            val user = auth.currentUser ?: error("Not signed in.")
            user.updatePassword(newPassword).await()
            Unit // Convert Void to Unit
        }.mapError(::toFriendlyAuthError)
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        if (email.isBlank()) return Result.failure(IllegalArgumentException("Enter an email address."))
        return runCatching {
            auth.sendPasswordResetEmail(email.trim()).await()
            Unit
        }.mapError(::toFriendlyAuthError)
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun addMessage(text: String, latitude: Double, longitude: Double): Result<Unit> {
        if (isGuestUser()) {
            return Result.failure(
                IllegalStateException("Guests can't write messages. Please register or log in.")
            )
        }
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        val authorUsername =
            fetchUsernameForUid(uid).takeUnless { it.isNullOrBlank() }
                ?: auth.currentUser?.email
                    ?.substringBefore('@')
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
                ?: "Member"
        val payload = mapOf(
            "text" to text,
            "latitude" to latitude,
            "longitude" to longitude,
            "createdAtEpochMs" to System.currentTimeMillis(),
            "authorId" to uid,
            "authorUsername" to authorUsername,
            "upvotes" to 0L,
            "downvotes" to 0L,
            "rating" to 0L
        )
        return runCatching {
            messagesCollection.add(payload).await()
        }.map { Unit }
    }

    suspend fun deleteMessage(messageId: String, allowDeleteAny: Boolean = false): Result<Unit> {
        if (messageId.isBlank()) return Result.failure(IllegalArgumentException("Invalid message ID."))
        if (isGuestUser()) {
            return Result.failure(
                IllegalStateException("Guests can't delete messages. Please register or log in.")
            )
        }
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        return runCatching {
            val doc = messagesCollection.document(messageId).get().await()
            val authorId = doc.getString("authorId").orEmpty()
            if (!allowDeleteAny && authorId != uid) {
                error("You can delete only your own messages.")
            }
            messagesCollection.document(messageId).delete().await()
        }.map { Unit }
    }

    suspend fun voteOnMessage(
        messageId: String,
        isUpvote: Boolean,
        allowUnlimitedVotes: Boolean = false
    ): Result<Unit> {
        if (messageId.isBlank()) return Result.failure(IllegalArgumentException("Invalid message ID."))
        if (isGuestUser()) {
            return Result.failure(
                IllegalStateException("Guests can't vote. Please register or log in.")
            )
        }
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

                if (allowUnlimitedVotes) {
                    val updates = if (isUpvote) {
                        mapOf(
                            "upvotes" to currentUpvotes + 1L,
                            "rating" to currentRating + 1L
                        )
                    } else {
                        mapOf(
                            "downvotes" to currentDownvotes + 1L,
                            "rating" to currentRating - 1L
                        )
                    }

                    if (!isUpvote && currentRating - 1L <= AUTO_DELETE_RATING_THRESHOLD) {
                        transaction.delete(docRef)
                        return@runTransaction null
                    }

                    transaction.update(
                        docRef,
                        updates
                    )
                    return@runTransaction null
                }

                val previousVote = voteSnapshot.getLong("vote") ?: 0L
                val newVote = if (isUpvote) 1L else -1L

                if (previousVote == newVote) {
                    return@runTransaction null
                }

                if (previousVote != 0L && previousVote != newVote) {
                    val updatedRating = currentRating - previousVote
                    val updates = if (previousVote == 1L) {
                        mapOf(
                            "upvotes" to currentUpvotes - 1L,
                            "rating" to updatedRating
                        )
                    } else {
                        mapOf(
                            "downvotes" to currentDownvotes - 1L,
                            "rating" to updatedRating
                        )
                    }

                    if (updatedRating <= AUTO_DELETE_RATING_THRESHOLD) {
                        transaction.delete(docRef)
                    } else {
                        transaction.delete(voteRef)
                        transaction.update(docRef, updates)
                    }
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
                        authorUsername = document.getString("authorUsername").orEmpty(),
                        upvotes = document.getLong("upvotes") ?: 0L,
                        downvotes = document.getLong("downvotes") ?: 0L,
                        rating = document.getLong("rating") ?: 0L
                    )
                }.orEmpty()

                trySend(Result.success(currentMessages))
            }

        awaitClose { listener.remove() }
    }

    private fun normalizeAndValidateUsername(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < USERNAME_MIN_LENGTH) {
            throw IllegalArgumentException("Username must be at least $USERNAME_MIN_LENGTH characters.")
        }
        if (trimmed.length > USERNAME_MAX_LENGTH) {
            throw IllegalArgumentException("Username can't be longer than $USERNAME_MAX_LENGTH characters.")
        }
        if ('\n' in trimmed || '\t' in trimmed || '\r' in trimmed) {
            throw IllegalArgumentException("Username can't include line breaks.")
        }
        if (!USERNAME_ALLOWED_PATTERN.matches(trimmed)) {
            throw IllegalArgumentException(
                "Username can use letters, numbers, spaces, apostrophes, underscores, or hyphens only."
            )
        }
        return trimmed
    }

    private suspend fun checkUsernameStillAvailable(normalizedUsername: String): Result<Unit> {
        val lowered = normalizedUsername.lowercase()
        val snapshot =
            usersCollection.whereEqualTo("usernameLower", lowered).limit(1).get().await()
        val conflictingId = snapshot.documents.firstOrNull()?.id ?: return Result.success(Unit)
        if (conflictingId.isNotBlank() && conflictingId != currentUserId()) {
            return Result.failure(IllegalStateException("That username is already taken. Choose another."))
        }
        return Result.success(Unit)
    }

    companion object {
        private const val MESSAGES_COLLECTION = "messages"
        private const val USERS_COLLECTION = "users"
        private const val VOTES_COLLECTION = "votes"
        private const val AUTO_DELETE_RATING_THRESHOLD = -2L
        private const val MIN_PASSWORD_LENGTH = 8
        private const val USERNAME_MIN_LENGTH = 2
        private const val USERNAME_MAX_LENGTH = 30
        private val USERNAME_ALLOWED_PATTERN =
            Regex("^[\\p{L}\\p{N}]{2}$|^[\\p{L}\\p{N}][\\p{L}\\p{N} _'-]*[\\p{L}\\p{N}]$")
    }

    private fun isPasswordValid(password: String): Boolean {
        return password.length >= MIN_PASSWORD_LENGTH &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() } &&
            password.any { it.isDigit() } &&
            password.any { !it.isLetterOrDigit() }
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
                "Password must be at least $MIN_PASSWORD_LENGTH characters and include uppercase, lowercase, number, and special character."
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

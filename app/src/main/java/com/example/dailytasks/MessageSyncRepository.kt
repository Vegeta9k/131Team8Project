package com.example.dailytasks

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

// Thin data layer around Firebase Auth + Firestore.
// This file knows how documents are shaped, which collections are queried, and which reads/writes
// need transactions so the rest of the app can stay focused on UI/state rules.
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
        // Reuse the current Firebase session when possible.
        auth.currentUser?.uid?.let { return it }
        // Otherwise create an anonymous session so the app always has a uid to work with.
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous authentication failed.")
    }

    // Reads a public username from the users collection for display and posting metadata.
    // Message documents also store a username snapshot, but account/profile screens refresh from here.
    suspend fun fetchUsernameForUid(uid: String): String? {
        if (uid.isBlank()) return null
        return runCatching {
            val doc = usersCollection.document(uid).get().await()
            if (!doc.exists()) return@runCatching null
            doc.getString("username")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // Reloads the signed-in member's username from Firestore.
    // Guests intentionally return null because anonymous accounts do not have profile docs.
    suspend fun refreshCurrentUsername(): String? {
        val uid = currentUserId() ?: return null
        if (isGuestUser()) return null
        return fetchUsernameForUid(uid)
    }

    // Creates an email/password account, validates the username, and stores the profile document.
    // The profile document keeps both the display form and a lowercase copy for uniqueness checks.
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
        // Normalize first so both validation and uniqueness checks operate on the same value.
        val normalizedUsername = runCatching { normalizeAndValidateUsername(username) }
            .getOrElse { return Result.failure(it) }
        return runCatching {
            // This query may be blocked by Firestore rules in some environments, so the code only
            // treats it as fatal when the failure is not a permission problem.
            checkUsernameStillAvailable(normalizedUsername).getOrElse { error ->
                if (!error.isPermissionDenied()) throw error
            }

            val result = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val uid = result.user?.uid ?: error("Registration failed.")

            // Store both the display form and a lowercase copy to support case-insensitive lookups.
            val profile = mapOf(
                "username" to normalizedUsername,
                "usernameLower" to normalizedUsername.lowercase()
            )
            usersCollection.document(uid).set(profile).await()
            uid
        }.mapError(::toFriendlyAuthError)
    }

    // Signs an existing member in and returns the Firebase user id on success.
    suspend fun signInWithEmailPassword(email: String, password: String): Result<String> {
        val trimmed = email.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Enter an email address."))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        return runCatching {
            val result = auth.signInWithEmailAndPassword(trimmed, password).await()
            result.user?.uid ?: error("Sign-in failed.")
        }.mapError(::toFriendlyAuthError)
    }

    // Lets a signed-in member replace their password after validation in the UI.
    suspend fun updatePassword(newPassword: String): Result<Unit> {
        if (newPassword.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        return runCatching {
            val user = auth.currentUser ?: error("Not signed in.")
            user.updatePassword(newPassword).await()
            Unit // Convert Void to Unit
        }.mapError(::toFriendlyAuthError)
    }

    // Triggers Firebase's password reset email flow.
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

    // Writes a new map message and snapshots the author's public name into the message document.
    // That snapshot lets the map render author names without joining back to the users collection.
    suspend fun addMessage(text: String, latitude: Double, longitude: Double): Result<Unit> {
        if (isGuestUser()) {
            return Result.failure(
                IllegalStateException("Guests can't write messages. Please register or log in.")
            )
        }
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        // Prefer the explicit Firestore profile username, then fall back to an email-derived label.
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
            // Client timestamp is enough here because messages are only displayed/sorted in-app.
            "createdAtEpochMs" to System.currentTimeMillis(),
            "authorId" to uid,
            "authorUsername" to authorUsername,
            "upvotes" to 0L,
            "downvotes" to 0L,
            "rating" to 0L
        )
        return runCatching {
            messagesCollection.add(payload).await()
        }.map { }
    }

    // Deletes a message after confirming the current user is allowed to remove it.
    // The owner check happens by re-reading the message document so the client is not trusted blindly.
    suspend fun deleteMessage(messageId: String, allowDeleteAny: Boolean = false): Result<Unit> {
        if (messageId.isBlank()) return Result.failure(IllegalArgumentException("Invalid message ID."))
        if (isGuestUser()) {
            return Result.failure(
                IllegalStateException("Guests can't delete messages. Please register or log in.")
            )
        }
        val uid = runCatching { ensureSignedIn() }.getOrElse { return Result.failure(it) }
        return runCatching {
            // Re-read the document before delete so ownership is checked against server data.
            val doc = messagesCollection.document(messageId).get().await()
            val authorId = doc.getString("authorId").orEmpty()
            if (!allowDeleteAny && authorId != uid) {
                error("You can delete only your own messages.")
            }
            messagesCollection.document(messageId).delete().await()
        }.map { }
    }

    // Stores one vote per member and keeps the aggregate counters in sync in one transaction.
    // The nested votes subcollection tracks each member's choice, while the parent message stores
    // aggregate counts so the UI can render scores cheaply.
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
                // Read both the message counters and this user's previous vote inside one transaction
                // so the final counts stay consistent even under concurrent voting.
                val snapshot = transaction.get(docRef)
                val voteSnapshot = transaction.get(voteRef)
                if (!snapshot.exists()) {
                    return@runTransaction null
                }

                val currentUpvotes = snapshot.getLong("upvotes") ?: 0L
                val currentDownvotes = snapshot.getLong("downvotes") ?: 0L
                val currentRating = snapshot.getLong("rating") ?: (currentUpvotes - currentDownvotes)

                if (allowUnlimitedVotes) {
                    // Admin-style voting skips per-user vote tracking and simply increments totals.
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

                    // Downvotes can automatically remove very poorly rated messages.
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

                // Tapping the same vote twice is treated as a no-op.
                if (previousVote == newVote) {
                    return@runTransaction null
                }

                // If the user switches from upvote to downvote or vice versa, first remove the old
                // vote's effect before deciding whether the message should survive.
                if (previousVote != 0L) {
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
                        // Remove the old vote record entirely; a later branch may recreate it.
                        transaction.delete(voteRef)
                        transaction.update(docRef, updates)
                    }
                    return@runTransaction null
                }

                // Fresh vote path: compute how each counter should change relative to the old state.
                val upvoteDelta = when {
                    newVote == 1L -> 1L
                    else -> 0L
                }
                val downvoteDelta = when {
                    newVote == -1L -> 1L
                    else -> 0L
                }
                val updatedRating = currentRating + newVote

                if (updatedRating <= AUTO_DELETE_RATING_THRESHOLD) {
                    transaction.delete(docRef)
                } else {
                    // Persist the user's vote so future taps know whether this is a repeat or swap.
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
        }.map { }
    }

    // Streams the whole messages collection so the UI stays live as pins are added or updated.
    // Each snapshot is converted into plain Kotlin models before leaving the repository.
    fun observeMessages(): Flow<Result<List<LocationMessage>>> = callbackFlow {
        val listener = messagesCollection
            .orderBy("createdAtEpochMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(Result.failure(error))
                    return@addSnapshotListener
                }

                // Map Firestore documents into immutable domain models consumed by the ViewModel/UI.
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

        // Remove the Firestore listener when the flow collector is cancelled.
        awaitClose { listener.remove() }
    }

    // Enforces the app's username rules before data is sent to Firestore.
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

    // Prevents duplicate public usernames by checking for an existing lowercase match.
    private suspend fun checkUsernameStillAvailable(normalizedUsername: String): Result<Unit> {
        val lowered = normalizedUsername.lowercase()
        // Usernames are compared case-insensitively by querying the normalized lowercase field.
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
        // Mirrors the password checklist shown in the registration UI.
        return password.length >= MIN_PASSWORD_LENGTH &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() } &&
            password.any { it.isDigit() } &&
            password.any { !it.isLetterOrDigit() }
    }

    private fun toFriendlyAuthError(throwable: Throwable): Throwable {
        val authErrorCode = (throwable as? FirebaseAuthException)?.errorCode
        // Translate low-level Firebase error codes into messages that make sense in the app's forms.
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

    private fun Throwable.isPermissionDenied(): Boolean {
        // Helper used where a Firestore rule failure should be handled differently from other errors.
        return this is FirebaseFirestoreException &&
            code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }
}

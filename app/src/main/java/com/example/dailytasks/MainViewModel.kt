package com.example.dailytasks

import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Owns the app's long-lived state.
// Compose screens render these StateFlows, while this ViewModel decides when to talk to Firebase,
// when a user is allowed to read/write/vote, and which preferences should survive app restarts.
class MainViewModel(application: Application) : AndroidViewModel(application) {
    // If Firebase is missing or misconfigured, the app falls back to a null repository and the UI
    // surfaces a friendly configuration error instead of crashing.
    private val repository = runCatching { MessageSyncRepository() }.getOrNull()
    // Small UI preferences and read-history markers are persisted locally here.
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profanityWords = application.resources
        .getStringArray(R.array.profanity_filter_vocabulary)
        .toList()
    private val profanityPatterns = profanityWords.map(::buildProfanityPattern)
    private val adminEmails = application.resources
        .getStringArray(R.array.admin_account_emails)
        .map(::normalizeEmail)
        .filter { it.isNotBlank() }
        .toSet()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _selectedLatLng = MutableStateFlow<LatLng?>(null)
    val selectedLatLng: StateFlow<LatLng?> = _selectedLatLng.asStateFlow()

    private val _userLatLng = MutableStateFlow<LatLng?>(null)
    val userLatLng: StateFlow<LatLng?> = _userLatLng.asStateFlow()

    private val _messages = MutableStateFlow<List<LocationMessage>>(emptyList())
    val messages: StateFlow<List<LocationMessage>> = _messages.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _isGuest = MutableStateFlow(false)
    val isGuest: StateFlow<Boolean> = _isGuest.asStateFlow()

    private val _authStateResolved = MutableStateFlow(false)
    val authStateResolved: StateFlow<Boolean> = _authStateResolved.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _currentUsername = MutableStateFlow("")
    val currentUsername: StateFlow<String> = _currentUsername.asStateFlow()

    private val _currentUserEmail = MutableStateFlow("")
    val currentUserEmail: StateFlow<String> = _currentUserEmail.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _darkThemeEnabled = MutableStateFlow(loadDarkThemeEnabled())
    val darkThemeEnabled: StateFlow<Boolean> = _darkThemeEnabled.asStateFlow()

    private val _profanityFilterEnabled = MutableStateFlow(loadProfanityFilterEnabled())
    val profanityFilterEnabled: StateFlow<Boolean> = _profanityFilterEnabled.asStateFlow()

    private val _profanityDisableWarningAcknowledged =
        MutableStateFlow(loadProfanityDisableWarningAcknowledged())
    val profanityDisableWarningAcknowledged: StateFlow<Boolean> =
        _profanityDisableWarningAcknowledged.asStateFlow()

    private val _readMessageIds = MutableStateFlow(loadReadMessageIds())
    val readMessageIds: StateFlow<Set<String>> = _readMessageIds.asStateFlow()
    private val _myMessagesSortOrder = MutableStateFlow(MyMessagesSortOrder.DATE)
    val myMessagesSortOrder: StateFlow<MyMessagesSortOrder> = _myMessagesSortOrder.asStateFlow()
    private val _pendingNewPassword = MutableStateFlow<String?>(null)
    private var messagesObserverJob: Job? = null
    private var knownUpvotes: Map<String, Long> = emptyMap()

    // Derives the "nearby" list from the full Firestore snapshot plus the user's latest GPS fix.
    // This is what enforces the 150m rule for reading and voting in the UI.
    val nearbyMessages = combine(messages, userLatLng) { allMessages, user ->
        if (user == null) {
            emptyList()
        } else {
            allMessages.filter { message ->
                distanceMeters(
                    user.latitude,
                    user.longitude,
                    message.latitude,
                    message.longitude
                ) <= NEARBY_RADIUS_METERS
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Produces the "My Messages" screen content by filtering the shared message list to the
    // current author's posts, then applying the active local sort mode.
    val myMessages = combine(messages, currentUserId, myMessagesSortOrder) { allMessages, uid, sortOrder ->
        if (uid.isNullOrBlank()) {
            emptyList()
        } else {
            allMessages
                .filter { it.authorId == uid }
                .sortedWith(sortOrder.comparator)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            _authStateResolved.value = true
        } else {
            // Restore the saved Firebase session on app startup.
            restoreExistingSession(repo)
            // If a Firebase session already exists, begin streaming messages immediately.
            startObservingMessages()
            viewModelScope.launch {
                // Member usernames live in Firestore, so they need one extra fetch after auth restore.
                if (repo.isSignedIn() && !repo.isGuestUser()) {
                    _currentUsername.value = repo.refreshCurrentUsername().orEmpty()
                }
            }
        }
    }

    // Signs in anonymously for guest mode.
    // Guests can read nearby messages and browse the map, but the rest of the code prevents them
    // from posting, deleting, or voting.
    fun signInAsGuest(onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            // Local fallback used only when Firebase is unavailable during development.
            _isSignedIn.value = false
            _isGuest.value = true
            _isAdmin.value = false
            _currentUserId.value = null
            _currentUserEmail.value = ""
            onFinished(true)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            withRepositoryTimeout { repo.ensureSignedIn() }
                .onSuccess { uid ->
                    // Anonymous Firebase users still get a uid, which is enough to establish
                    // a session and attach the shared message listener.
                    applyAuthenticatedSession(
                        uid = uid,
                        isGuest = repo.isGuestUser(),
                        isAdmin = resolveIsAdmin(repo),
                        username = "",
                        email = repo.currentUserEmail().orEmpty()
                    )
                    startObservingMessages()
                    onFinished(true)
                }
                .onFailure {
                    // Any auth failure resets UI state so the login screen stays consistent.
                    clearAuthenticatedSession(it.message ?: "Authentication failed.")
                    onFinished(false)
                }
        }
    }

    // Creates a member account, then refreshes the UI state from the new Firebase session.
    // After Firebase creates the auth user, the repository also creates a profile document that
    // stores the public username shown on messages.
    fun registerWithEmailPassword(
        email: String,
        password: String,
        username: String,
        onFinished: (Boolean) -> Unit
    ) {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            onFinished(false)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            withRepositoryTimeout {
                repo.registerWithEmailPassword(email, password, username)
            }.getOrElse { Result.failure(it) }
                .onSuccess { uid ->
                    // The repository has already created the auth account plus profile document,
                    // so the ViewModel just mirrors that session into UI state.
                    applyAuthenticatedSession(
                        uid = uid,
                        isGuest = false,
                        isAdmin = resolveIsAdmin(repo),
                        username = repo.refreshCurrentUsername().orEmpty(),
                        email = repo.currentUserEmail().orEmpty()
                    )
                    startObservingMessages()
                    // Refresh once more from Firestore so the UI is definitely showing the stored
                    // canonical username, not only the raw form input.
                    loadCurrentUsername()
                    onFinished(true)
                }
                .onFailure { e ->
                    clearAuthenticatedSession(e.message ?: "Could not create account.")
                    onFinished(false)
                }
        }
    }

    // Signs a returning member in and then reloads their public profile fields used by the UI.
    fun signInWithEmailPassword(email: String, password: String, onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            onFinished(false)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            withRepositoryTimeout {
                repo.signInWithEmailPassword(email, password)
            }.getOrElse { Result.failure(it) }
                .onSuccess { uid ->
                    // Sign-in reuses the same session setup path as registration.
                    applyAuthenticatedSession(
                        uid = uid,
                        isGuest = false,
                        isAdmin = resolveIsAdmin(repo),
                        username = repo.refreshCurrentUsername().orEmpty(),
                        email = repo.currentUserEmail().orEmpty()
                    )
                    startObservingMessages()
                    loadCurrentUsername()
                    onFinished(true)
                }
                .onFailure { e ->
                    clearAuthenticatedSession(e.message ?: "Could not sign in.")
                    onFinished(false)
                }
        }
    }

    fun sendPasswordResetEmail(email: String, onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured."
            onFinished(false)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            withRepositoryTimeout {
                repo.sendPasswordResetEmail(email.trim())
            }.getOrElse { Result.failure(it) }
                .onSuccess {
                    // The success text is routed through syncError because the auth screen already
                    // has a visible message area bound to that field.
                    _syncError.value = "Password reset email sent! Check your email and click the link to reset your password securely."
                    onFinished(true)
                }
                .onFailure { e ->
                    _syncError.value = e.message ?: "Failed to send reset email."
                    onFinished(false)
                }
        }
    }

    // Clears auth-dependent UI state and stops the Firestore listener.
    // It also drops local "read" history so a future guest/member session starts fresh.
    fun signOut() {
        repository?.signOut()
        stopObservingMessages()
        // Reset all session-derived state so the next login starts from a clean slate.
        _isSignedIn.value = false
        _isGuest.value = false
        _isAdmin.value = false
        _currentUserId.value = null
        _currentUsername.value = ""
        _currentUserEmail.value = ""
        _selectedLatLng.value = null
        _syncError.value = null
        clearReadMessageIds()
    }

    // Marks a nearby message as read after the user taps it.
    // Read ids are persisted in SharedPreferences so marker styling survives process death.
    fun markMessageReadIfNearby(message: LocationMessage) {
        if (!canReadMessage(message)) return
        val id = message.id.ifBlank { return }
        _readMessageIds.update { current ->
            if (id in current) return@update current
            // Store a copy rather than mutating the existing Set because StateFlow values should
            // behave like immutable snapshots for Compose.
            val next = HashSet(current).apply { add(id) }
            preferences.edit().putStringSet(KEY_READ_MESSAGE_IDS, next).apply()
            next.toSet()
        }
    }

    fun updateDraftText(value: String) {
        _draftText.value = value
    }

    fun updateSelectedLocation(latLng: LatLng) {
        _selectedLatLng.value = latLng
    }

    fun clearSelectedLocation() {
        _selectedLatLng.value = null
    }

    fun updateUserLocation(location: Location) {
        _userLatLng.value = LatLng(location.latitude, location.longitude)
    }

    // Validates permissions, location, and text before posting a new message.
    // The selected map point must be within range of the current GPS location before Firestore
    // is allowed to receive the write.
    fun saveMessage(onFinished: (Boolean) -> Unit = {}) {
        if (_isGuest.value) {
            _syncError.value = "Guests can't write messages. Please register or log in."
            onFinished(false)
            return
        }
        val repo = repository ?: run {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            onFinished(false)
            return
        }

        val text = _draftText.value.trim()
        if (text.isBlank()) {
            // Blank messages are silently rejected because the UI already makes the empty state obvious.
            onFinished(false)
            return
        }

        // If the user did not manually tap a destination, fall back to their current location.
        val destination = _selectedLatLng.value ?: _userLatLng.value ?: run {
            _syncError.value = "Location not ready yet. Wait for GPS, then try again."
            onFinished(false)
            return
        }
        if (!canWriteAt(destination)) {
            _syncError.value = "You can only write messages within 150m of your current location."
            onFinished(false)
            return
        }

        viewModelScope.launch {
            repo
                .addMessage(
                    text = text,
                    latitude = destination.latitude,
                    longitude = destination.longitude
                )
                .onSuccess {
                    // Clear compose state only after the backend confirms the write.
                    _draftText.update { "" }
                    _selectedLatLng.update { null }
                    _syncError.update { null }
                    onFinished(true)
                }
                .onFailure { throwable ->
                    _syncError.update { throwable.message ?: "Message upload failed." }
                    onFinished(false)
                }
        }
    }

    fun clearSyncError() {
        _syncError.value = null
    }

    // Allows deletion of the user's own messages, or nearby messages for admins.
    // Non-admin members cannot remove someone else's pin even if they can see it on the map.
    fun deleteMessage(message: LocationMessage) {
        if (_isGuest.value) {
            _syncError.value = "Guests can't delete messages. Please register or log in."
            return
        }
        val repo = repository ?: run {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            return
        }
        val uid = _currentUserId.value
        val canDeleteOwnMessage = !uid.isNullOrBlank() && message.authorId == uid
        val canDeleteAsNearbyAdmin = _isAdmin.value && canReadMessage(message)
        // The ViewModel enforces coarse app rules before the repository performs the final owner check.
        if (!canDeleteOwnMessage && !canDeleteAsNearbyAdmin) {
            _syncError.value = if (_isAdmin.value) {
                "Admins can delete any nearby message. Move within 150m to delete it."
            } else {
                "You can delete only your own messages."
            }
            return
        }
        viewModelScope.launch {
            repo.deleteMessage(message.id, allowDeleteAny = canDeleteAsNearbyAdmin)
                .onSuccess {
                    _syncError.value = null
                }
                .onFailure { throwable ->
                    _syncError.value = throwable.message ?: "Message delete failed."
                }
        }
    }

    fun upvoteMessage(message: LocationMessage) {
        submitVote(message, isUpvote = true)
    }

    fun downvoteMessage(message: LocationMessage) {
        submitVote(message, isUpvote = false)
    }

    fun setDarkThemeEnabled(enabled: Boolean) {
        _darkThemeEnabled.value = enabled
        preferences.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    fun setProfanityFilterEnabled(enabled: Boolean) {
        _profanityFilterEnabled.value = enabled
        preferences.edit().putBoolean(KEY_PROFANITY_FILTER, enabled).apply()
    }

    fun acknowledgeProfanityDisableWarning() {
        _profanityDisableWarningAcknowledged.value = true
        preferences.edit().putBoolean(KEY_PROFANITY_DISABLE_WARNING_ACKNOWLEDGED, true).apply()
    }

    fun setMyMessagesSortOrder(sortOrder: MyMessagesSortOrder) {
        _myMessagesSortOrder.value = sortOrder
    }

    // Hides message text until the user is physically close enough to the pin.
    // Once the distance rule is satisfied, the profanity filter may still sanitize the text.
    fun displayMessageText(message: LocationMessage): String {
        if (!canReadMessage(message)) {
            return "Move within 150m to read this message."
        }
        return if (_profanityFilterEnabled.value) {
            filterProfanity(message.text)
        } else {
            message.text
        }
    }

    fun displayMyMessageText(message: LocationMessage): String {
        return if (_profanityFilterEnabled.value) {
            filterProfanity(message.text)
        } else {
            message.text
        }
    }

    fun displayAuthorUsername(message: LocationMessage): String {
        val name = message.authorUsername.trim()
        if (name.isNotEmpty()) return name
        return "Unknown user"
    }

    // Rehydrates auth state from Firebase when the app is launched again.
    // Firebase persists the underlying session, so this avoids forcing the user to log in every time.
    private fun restoreExistingSession(repo: MessageSyncRepository) {
        val existingUid = repo.currentUserId()
        _isSignedIn.value = !existingUid.isNullOrBlank()
        _isGuest.value = repo.isGuestUser()
        _isAdmin.value = resolveIsAdmin(repo)
        _currentUserId.value = existingUid
        _currentUserEmail.value = repo.currentUserEmail().orEmpty()
        _authStateResolved.value = true
    }

    private fun applyAuthenticatedSession(
        uid: String,
        isGuest: Boolean,
        isAdmin: Boolean,
        username: String,
        email: String
    ) {
        // Central helper used by guest login, register, and sign-in so session state is updated consistently.
        _isSignedIn.value = true
        _isGuest.value = isGuest
        _isAdmin.value = isAdmin
        _currentUserId.value = uid
        _currentUsername.value = username
        _currentUserEmail.value = email
        _syncError.value = null
    }

    private fun clearAuthenticatedSession(errorMessage: String) {
        // Central failure path used when auth/session setup does not complete successfully.
        _isSignedIn.value = false
        _isGuest.value = false
        _isAdmin.value = false
        _currentUserId.value = null
        _currentUsername.value = ""
        _currentUserEmail.value = ""
        _syncError.value = errorMessage
    }

    private fun loadCurrentUsername() {
        val repo = repository ?: return
        viewModelScope.launch {
            // A small helper for screens that need the freshest server-side username after auth events.
            _currentUsername.value = withRepositoryTimeout {
                repo.refreshCurrentUsername()
            }.getOrNull().orEmpty()
        }
    }

    private suspend fun <T> withRepositoryTimeout(block: suspend () -> T): Result<T> {
        return try {
            // Wrap repository calls so slow network operations can return friendly errors to the UI.
            Result.success(withTimeout(AUTH_REQUEST_TIMEOUT_MS) { block() })
        } catch (_: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    "The request took too long. Check your internet connection and try again."
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // Starts one live Firestore subscription that feeds the whole app's message state.
    // Every screen that shows messages reads from this shared in-memory list instead of querying
    // Firestore independently.
    private fun startObservingMessages() {
        val repo = repository ?: return
        if (!_isSignedIn.value) return
        if (messagesObserverJob?.isActive == true) return
        // Reset the previous-score cache whenever a fresh listener lifecycle begins.
        knownUpvotes = emptyMap()
        messagesObserverJob = viewModelScope.launch {
            repo.observeMessages().collect { result ->
                result
                    .onSuccess { cloudMessages ->
                        // Keep all downstream UI derived from one authoritative in-memory snapshot.
                        checkForNewUpvotes(cloudMessages)
                        _messages.value = cloudMessages
                        // Remove read markers for messages that no longer exist in Firestore.
                        pruneReadMessageIds(cloudMessages.map { it.id }.toSet())
                        _syncError.value = null
                    }
                    .onFailure {
                        _syncError.value = it.message ?: "Could not sync cloud messages."
                    }
            }
        }
    }

    // Compares the latest message scores with the previous snapshot to detect new upvotes.
    // Notifications are only raised for messages authored by the currently signed-in user.
    private fun checkForNewUpvotes(cloudMessages: List<LocationMessage>) {
        val uid = _currentUserId.value ?: return
        val context = getApplication<Application>()
        cloudMessages
            .filter { it.authorId == uid }
            .forEach { message ->
                val previous = knownUpvotes[message.id]
                if (previous != null && message.upvotes > previous) {
                    UpvoteNotifier.notifyUpvote(context, message.text)
                }
            }
        knownUpvotes = cloudMessages.associate { it.id to it.upvotes }
    }

    private fun stopObservingMessages() {
        // Used on sign-out so Firestore is not left streaming in the background.
        messagesObserverJob?.cancel()
        messagesObserverJob = null
        knownUpvotes = emptyMap()
    }

    fun canReadMessage(message: LocationMessage): Boolean {
        return isWithinNearbyRange(message.latitude, message.longitude)
    }

    fun canVoteOnMessage(message: LocationMessage): Boolean {
        return canReadMessage(message)
    }

    fun canReadOwnMessage(message: LocationMessage): Boolean {
        return message.authorId == _currentUserId.value || canReadMessage(message)
    }

    fun canVoteOnOwnMessage(message: LocationMessage): Boolean {
        return message.authorId == _currentUserId.value || canVoteOnMessage(message)
    }

    fun canDeleteMessageFromMap(message: LocationMessage): Boolean {
        return _isAdmin.value && canReadMessage(message)
    }

    fun canWriteSelectedMessage(): Boolean {
        val destination = _selectedLatLng.value ?: return false
        return canWriteAt(destination)
    }

    private fun loadDarkThemeEnabled(): Boolean {
        return preferences.getBoolean(KEY_DARK_THEME, false)
    }

    private fun loadProfanityFilterEnabled(): Boolean {
        return preferences.getBoolean(KEY_PROFANITY_FILTER, true)
    }

    private fun loadProfanityDisableWarningAcknowledged(): Boolean {
        return preferences.getBoolean(KEY_PROFANITY_DISABLE_WARNING_ACKNOWLEDGED, false)
    }

    private fun loadReadMessageIds(): Set<String> {
        return preferences.getStringSet(KEY_READ_MESSAGE_IDS, emptySet())?.toSet().orEmpty()
    }

    private fun pruneReadMessageIds(validIds: Set<String>) {
        _readMessageIds.update { stored ->
            val pruned = stored.filter { it in validIds }.toSet()
            if (pruned.size != stored.size) {
                // Persist the trimmed set so stale ids are not restored on next app launch.
                preferences.edit().putStringSet(KEY_READ_MESSAGE_IDS, HashSet(pruned)).apply()
            }
            pruned
        }
    }

    private fun clearReadMessageIds() {
        _readMessageIds.value = emptySet()
        preferences.edit().remove(KEY_READ_MESSAGE_IDS).apply()
    }

    private fun resolveIsAdmin(repo: MessageSyncRepository): Boolean {
        if (repo.isGuestUser()) return false
        // Admin access is granted purely by a local allowlist of normalized emails.
        val normalizedEmail = normalizeEmail(repo.currentUserEmail().orEmpty())
        return normalizedEmail.isNotBlank() && normalizedEmail in adminEmails
    }

    // Replaces matched profanity with asterisks while preserving the rest of the message.
    // The patterns intentionally catch simple obfuscations like "@" for "a" or "0" for "o".
    private fun filterProfanity(text: String): String {
        var sanitized = text
        profanityPatterns.forEach { pattern ->
            sanitized = pattern.replace(sanitized) { matchResult ->
                val lettersOnly = matchResult.value.count { it.isLetterOrDigit() }
                "*".repeat(lettersOnly.coerceAtLeast(4))
            }
        }
        return sanitized
    }

    private fun buildProfanityPattern(word: String): Regex {
        val normalizedWord = normalizeForModeration(word)
        val separator = "[\\W_]*"
        // Build a forgiving regex so simple substitutions like 3->e or @->a still get caught.
        val body = normalizedWord.map { char ->
            val charPattern = when (char) {
                'a' -> "[a4@]+"
                'b' -> "[b8]+"
                'e' -> "[e3]+"
                'g' -> "[g69]+"
                'i' -> "[i1!|l]+"
                'l' -> "[l1!|i]+"
                'o' -> "[o0]+"
                's' -> "[s5$]+"
                't' -> "[t7+]+"
                'z' -> "[z2]+"
                else -> Regex.escape(char.toString()) + "+"
            }
            "$charPattern$separator"
        }.joinToString("")
        return Regex("(?<![A-Za-z0-9])$body(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    }

    private fun normalizeForModeration(text: String): String {
        // Normalize common leetspeak/noise before building patterns from the source vocabulary.
        return text
            .lowercase(Locale.US)
            .replace("@", "a")
            .replace("$", "s")
            .replace("0", "o")
            .replace("1", "i")
            .replace("3", "e")
            .replace("4", "a")
            .replace("5", "s")
            .replace("7", "t")
            .replace("8", "b")
            .replace("9", "g")
            .replace(Regex("[^a-z\\s]"), "")
            .replace(Regex("(.)\\1{2,}"), "$1")
            .trim()
    }

    private fun normalizeEmail(email: String): String {
        return email.trim().lowercase(Locale.US)
    }

    // Applies the app's voting rules before delegating the update to Firestore.
    // Votes are blocked for guests and for users outside the 150m read radius.
    private fun submitVote(message: LocationMessage, isUpvote: Boolean) {
        if (_isGuest.value) {
            _syncError.value = "Guests can't vote. Please register or log in."
            return
        }
        if (!canVoteOnMessage(message)) {
            _syncError.value = "You can only vote on messages within 150m of your current location."
            return
        }
        val repo = repository ?: run {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            return
        }
        viewModelScope.launch {
            repo.voteOnMessage(
                message.id,
                isUpvote,
                allowUnlimitedVotes = _isAdmin.value
            )
                .onSuccess {
                    // A successful vote needs no extra local mutation because the live Firestore
                    // listener will deliver the updated counts back into _messages.
                    _syncError.value = null
                }
                .onFailure { throwable ->
                    _syncError.value = throwable.message ?: "Message vote failed."
                }
        }
    }

    private fun canWriteAt(destination: LatLng): Boolean {
        val user = _userLatLng.value ?: return false
        return distanceMeters(
            user.latitude,
            user.longitude,
            destination.latitude,
            destination.longitude
        ) <= NEARBY_RADIUS_METERS
    }

    private fun isWithinNearbyRange(latitude: Double, longitude: Double): Boolean {
        val user = _userLatLng.value ?: return false
        return distanceMeters(
            user.latitude,
            user.longitude,
            latitude,
            longitude
        ) <= NEARBY_RADIUS_METERS
    }

    private fun distanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        // Android's built-in location helper handles the geodesic distance calculation for us.
        val output = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, output)
        return output[0]
    }

    companion object {
        const val NEARBY_RADIUS_METERS = 150f
        private const val AUTH_REQUEST_TIMEOUT_MS = 15_000L
        private const val PREFS_NAME = "map_messages_preferences"
        private const val KEY_DARK_THEME = "dark_theme_enabled"
        private const val KEY_PROFANITY_FILTER = "profanity_filter_enabled"
        private const val KEY_PROFANITY_DISABLE_WARNING_ACKNOWLEDGED =
            "profanity_disable_warning_acknowledged"
        private const val KEY_READ_MESSAGE_IDS = "read_message_ids"
    }
}

enum class MyMessagesSortOrder(val label: String, val comparator: Comparator<LocationMessage>) {
    DATE(
        label = "Date",
        comparator = compareByDescending<LocationMessage> { it.createdAtEpochMs }
            .thenByDescending { it.upvotes }
    ),
    UPVOTES(
        label = "Upvotes",
        comparator = compareByDescending<LocationMessage> { it.upvotes }
            .thenByDescending { it.createdAtEpochMs }
    )
}

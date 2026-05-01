package com.example.dailytasks

import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = runCatching { MessageSyncRepository() }.getOrNull()
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _darkThemeEnabled = MutableStateFlow(loadDarkThemeEnabled())
    val darkThemeEnabled: StateFlow<Boolean> = _darkThemeEnabled.asStateFlow()

    private val _profanityFilterEnabled = MutableStateFlow(loadProfanityFilterEnabled())
    val profanityFilterEnabled: StateFlow<Boolean> = _profanityFilterEnabled.asStateFlow()

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

    val myMessages = combine(messages, currentUserId) { allMessages, uid ->
        if (uid.isNullOrBlank()) {
            emptyList()
        } else {
            allMessages.filter { it.authorId == uid }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
        } else {
            viewModelScope.launch {
                repo.observeMessages().collect { result ->
                    result
                        .onSuccess { cloudMessages ->
                            _messages.value = cloudMessages
                            _syncError.value = null
                        }
                        .onFailure {
                            _syncError.value = it.message ?: "Could not sync cloud messages."
                        }
                }
            }
        }
    }

    /**
     * Signs in anonymously (guest). Call from the login screen before entering the main app.
     */
    fun signInAsGuest(onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            _isSignedIn.value = false
            _isGuest.value = true
            _currentUserId.value = null
            onFinished(true)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            runCatching { repo.ensureSignedIn() }
                .onSuccess { uid ->
                    _isSignedIn.value = true
                    _isGuest.value = repo.isGuestUser()
                    _currentUserId.value = uid
                    _syncError.value = null
                    onFinished(true)
                }
                .onFailure {
                    _isSignedIn.value = false
                    _isGuest.value = false
                    _currentUserId.value = null
                    _syncError.value = it.message ?: "Authentication failed."
                    onFinished(false)
                }
        }
    }

    fun registerWithEmailPassword(email: String, password: String, onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            onFinished(false)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            repo.registerWithEmailPassword(email, password)
                .onSuccess { uid ->
                    _isSignedIn.value = true
                    _isGuest.value = false
                    _currentUserId.value = uid
                    _syncError.value = null
                    onFinished(true)
                }
                .onFailure { e ->
                    _isSignedIn.value = false
                    _isGuest.value = false
                    _currentUserId.value = null
                    _syncError.value = e.message ?: "Could not create account."
                    onFinished(false)
                }
        }
    }

    fun signInWithEmailPassword(email: String, password: String, onFinished: (Boolean) -> Unit) {
        val repo = repository
        if (repo == null) {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            onFinished(false)
            return
        }
        viewModelScope.launch {
            _syncError.value = null
            repo.signInWithEmailPassword(email, password)
                .onSuccess { uid ->
                    _isSignedIn.value = true
                    _isGuest.value = false
                    _currentUserId.value = uid
                    _syncError.value = null
                    onFinished(true)
                }
                .onFailure { e ->
                    _isSignedIn.value = false
                    _isGuest.value = false
                    _currentUserId.value = null
                    _syncError.value = e.message ?: "Could not sign in."
                    onFinished(false)
                }
        }
    }

    fun signOut() {
        repository?.signOut()
        _isSignedIn.value = false
        _isGuest.value = false
        _currentUserId.value = null
        _selectedLatLng.value = null
        _syncError.value = null
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

    fun saveMessage() {
        if (_isGuest.value) {
            _syncError.value = "Guests can't write messages. Please register or log in."
            return
        }
        val repo = repository ?: run {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            return
        }

        val text = _draftText.value.trim()
        if (text.isBlank()) return

        val destination = _selectedLatLng.value ?: _userLatLng.value ?: return

        viewModelScope.launch {
            repo
                .addMessage(
                    text = text,
                    latitude = destination.latitude,
                    longitude = destination.longitude
                )
                .onSuccess {
                    _draftText.update { "" }
                    _selectedLatLng.update { null }
                    _syncError.update { null }
                }
                .onFailure { throwable ->
                    _syncError.update { throwable.message ?: "Message upload failed." }
                }
        }
    }

    fun clearSyncError() {
        _syncError.value = null
    }

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
        if (uid.isNullOrBlank() || message.authorId != uid) {
            _syncError.value = "You can delete only your own messages."
            return
        }
        viewModelScope.launch {
            repo.deleteMessage(message.id)
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

    fun displayMessageText(message: LocationMessage): String {
        return if (_profanityFilterEnabled.value) {
            filterProfanity(message.text)
        } else {
            message.text
        }
    }

    private fun loadDarkThemeEnabled(): Boolean {
        return preferences.getBoolean(KEY_DARK_THEME, false)
    }

    private fun loadProfanityFilterEnabled(): Boolean {
        return preferences.getBoolean(KEY_PROFANITY_FILTER, false)
    }

    private fun filterProfanity(text: String): String {
        var sanitized = text
        PROFANITY_WORDS.forEach { word ->
            val regex = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
            sanitized = sanitized.replace(regex, "****")
        }
        return sanitized
    }

    private fun submitVote(message: LocationMessage, isUpvote: Boolean) {
        if (_isGuest.value) {
            _syncError.value = "Guests can't vote. Please register or log in."
            return
        }
        val repo = repository ?: run {
            _syncError.value = "Firebase is not configured. Add app/google-services.json."
            return
        }
        viewModelScope.launch {
            repo.voteOnMessage(message.id, isUpvote)
                .onSuccess {
                    _syncError.value = null
                }
                .onFailure { throwable ->
                    _syncError.value = throwable.message ?: "Message vote failed."
                }
        }
    }

    private fun distanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        val output = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, output)
        return output[0]
    }

    companion object {
        const val NEARBY_RADIUS_METERS = 150f
        private const val PREFS_NAME = "map_messages_preferences"
        private const val KEY_DARK_THEME = "dark_theme_enabled"
        private const val KEY_PROFANITY_FILTER = "profanity_filter_enabled"
        private val PROFANITY_WORDS = listOf(
            "damn",
            "hell",
            "shit",
            "fuck",
            "bitch",
            "asshole",
            "bastard"
        )
    }
}

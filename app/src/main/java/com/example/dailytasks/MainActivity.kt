package com.example.dailytasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

private data class MessageMapMarkerIcons(
    val unread: BitmapDescriptor,
    val read: BitmapDescriptor,
    val unreadHighlighted: BitmapDescriptor,
    val readHighlighted: BitmapDescriptor
)

private fun bitmapDescriptorFromDrawable(
    context: Context,
    @DrawableRes resId: Int,
    targetWidthDp: Float = 44f
): BitmapDescriptor {
    val drawable = ContextCompat.getDrawable(context, resId)!!
    val density = context.resources.displayMetrics.density
    val targetPx = (targetWidthDp * density).toInt().coerceIn(24, 256)
    val srcW = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetPx
    val srcH = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetPx
    val aspect = srcH.toFloat() / srcW.toFloat().coerceAtLeast(0.01f)
    val w = targetPx
    val h = (targetPx * aspect).toInt().coerceAtLeast(1).coerceAtMost(512)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Marker icons are created during composition, so initialize Maps first.
        MapsInitializer.initialize(this)
        UpvoteNotifier.createChannel(this)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val context = LocalContext.current
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val darkThemeEnabled by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
            val authStateResolved by viewModel.authStateResolved.collectAsStateWithLifecycle()
            val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
            val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
            val shouldEnterMainApp = isSignedIn || isGuest
            MaterialTheme(colorScheme = if (darkThemeEnabled) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!authStateResolved) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading...",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else if (!shouldEnterMainApp) {
                        LoginScreen(
                            viewModel = viewModel,
                            onAuthenticated = {}
                        )
                    } else {
                        AppScreen(
                            viewModel = viewModel,
                            onLogout = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    viewModel: MainViewModel,
    onAuthenticated: () -> Unit
) {
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    var authDestination by remember { mutableStateOf(AuthDestination.HOME) }
    var isBusy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }

    BackHandler(enabled = authDestination != AuthDestination.HOME) {
        when (authDestination) {
            AuthDestination.FORGOT_PASSWORD -> authDestination = AuthDestination.LOGIN
            else -> authDestination = AuthDestination.HOME
        }
        viewModel.clearSyncError()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Message In a Bottle",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
                    Text(
            text = "Please continue to register, login, or guest login below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (authDestination) {
            AuthDestination.HOME -> {
                Button(
                    onClick = {
                        viewModel.clearSyncError()
                        authDestination = AuthDestination.REGISTER
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Register")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.clearSyncError()
                        authDestination = AuthDestination.LOGIN
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log in")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        viewModel.signInAsGuest { ok ->
                            isBusy = false
                            if (ok) onAuthenticated()
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Please wait..." else "Guest login")
                }
            }

            AuthDestination.LOGIN -> {
                TextButton(
                    onClick = {
                        viewModel.clearSyncError()
                        authDestination = AuthDestination.HOME
                    },
                    enabled = !isBusy,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("Back")
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { authDestination = AuthDestination.FORGOT_PASSWORD },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Forgot Password?")
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        viewModel.signInWithEmailPassword(email, password) { ok ->
                            isBusy = false
                            if (ok) onAuthenticated()
                        }
                    },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Please wait..." else "Log in")
                }
            }

            AuthDestination.REGISTER -> {
                val passwordsMatch = confirmPassword == password
                val passwordValid = isPasswordValid(password)
                val passwordLengthValid = password.length >= PASSWORD_REQUIREMENT_MIN_LENGTH
                val passwordHasUppercase = password.any { it.isUpperCase() }
                val passwordHasLowercase = password.any { it.isLowerCase() }
                val passwordHasDigit = password.any { it.isDigit() }
                val passwordHasSpecial = password.any { !it.isLetterOrDigit() }

                TextButton(
                    onClick = {
                        viewModel.clearSyncError()
                        authDestination = AuthDestination.HOME
                    },
                    enabled = !isBusy,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("Back")
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Username") },
                    supportingText = {
                        Text(
                            text = "Shown publicly on the map.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Password requirements:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "${if (passwordLengthValid) "✓" else "•"} At least $PASSWORD_REQUIREMENT_MIN_LENGTH characters",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            passwordLengthValid -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "${if (passwordHasUppercase) "✓" else "•"} One uppercase letter",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            passwordHasUppercase -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "${if (passwordHasLowercase) "✓" else "•"} One lowercase letter",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            passwordHasLowercase -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "${if (passwordHasDigit) "✓" else "•"} One number",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            passwordHasDigit -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "${if (passwordHasSpecial) "✓" else "•"} One special character",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            passwordHasSpecial -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.align(Alignment.Start)
                    )
                }
                if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Passwords do not match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Start)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (isBusy || !passwordValid || !passwordsMatch) return@Button
                        isBusy = true
                        viewModel.registerWithEmailPassword(email, password, username) { ok ->
                            isBusy = false
                            if (ok) onAuthenticated()
                        }
                    },
                    enabled = !isBusy && username.trim().length >= AUTH_USERNAME_PREVIEW_MIN_LENGTH &&
                        email.isNotBlank() && password.isNotBlank() &&
                        confirmPassword.isNotBlank() && passwordValid && passwordsMatch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Please wait..." else "Create account")
                }
            }

            AuthDestination.FORGOT_PASSWORD -> {
                TextButton(
                    onClick = {
                        viewModel.clearSyncError()
                        authDestination = AuthDestination.LOGIN
                    },
                    enabled = !isBusy,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("Back")
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        viewModel.clearSyncError()
                    },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        viewModel.sendPasswordResetEmail(email) { success ->
                            isBusy = false
                            if (success) {
                                authDestination = AuthDestination.LOGIN
                                email = ""
                            }
                        }
                    },
                    enabled = !isBusy && email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Sending..." else "Send Password Reset Email")
                }
            }
        }

        if (!syncError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = syncError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "By signing up and logging in, I acknowledge that this an application that is still in development.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppScreen(
    viewModel: MainViewModel,
    onLogout: () -> Unit
) {
    var destination by remember { mutableStateOf(AppDestination.MAIN) }

    when (destination) {
        AppDestination.MAIN -> {
            LocationMessagesScreen(
                viewModel = viewModel,
                onOpenSettings = { destination = AppDestination.SETTINGS },
                onOpenMyMessages = { destination = AppDestination.MY_MESSAGES }
            )
        }
        AppDestination.SETTINGS -> {
            SettingsScreen(
                darkThemeEnabled = viewModel.darkThemeEnabled.collectAsStateWithLifecycle().value,
                profanityFilterEnabled = viewModel.profanityFilterEnabled.collectAsStateWithLifecycle().value,
                profanityDisableWarningAcknowledged = viewModel.profanityDisableWarningAcknowledged.collectAsStateWithLifecycle().value,
                onThemeChanged = viewModel::setDarkThemeEnabled,
                onProfanityFilterChanged = viewModel::setProfanityFilterEnabled,
                onAcknowledgeProfanityDisableWarning = viewModel::acknowledgeProfanityDisableWarning,
                onLogout = {
                    viewModel.signOut()
                    onLogout()
                },
                onBack = { destination = AppDestination.MAIN }
            )
        }
        AppDestination.MY_MESSAGES -> {
            MyMessagesScreen(
                viewModel = viewModel,
                onBack = { destination = AppDestination.MAIN }
            )
        }
    }
}

@Composable
private fun LocationMessagesScreen(
    viewModel: MainViewModel = viewModel(),
    onOpenSettings: () -> Unit,
    onOpenMyMessages: () -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val nearbyMessages by viewModel.nearbyMessages.collectAsStateWithLifecycle()
    val readMessageIds by viewModel.readMessageIds.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val selectedLatLng by viewModel.selectedLatLng.collectAsStateWithLifecycle()
    val userLatLng by viewModel.userLatLng.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    var permissionsGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val cameraPositionState = rememberCameraPositionState()
    var popupMessageId by remember { mutableStateOf<String?>(null) }
    var isWritingMessage by remember { mutableStateOf(false) }
    val popupMessage = messages.firstOrNull { it.id == popupMessageId }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(userLatLng) {
        val user = userLatLng ?: return@LaunchedEffect
        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(user, 18f))
    }

    if (permissionsGranted) {
        LocationUpdatesEffect(onLocationUpdate = viewModel::updateUserLocation)
    }

    val mapMarkerIcons = rememberMessageMapMarkerIcons()

    Box(modifier = Modifier.fillMaxSize()) {
        MapSection(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            messages = messages,
            selectedLatLng = selectedLatLng,
            showMessageMarkers = !isWritingMessage,
            nearbyMessageIds = nearbyMessages.map { it.id }.toSet(),
            readMessageIds = readMessageIds,
            highlightedMessageId = popupMessageId,
            markerIcons = mapMarkerIcons,
            isMyLocationEnabled = permissionsGranted,
            onMapClick = {
                popupMessageId = null
                if (isWritingMessage && !isGuest) {
                    viewModel.updateSelectedLocation(it)
                }
            },
            onMarkerClick = { message ->
                popupMessageId = message.id
                viewModel.markMessageReadIfNearby(message)
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (!isGuest) {
                            TextButton(onClick = onOpenMyMessages) {
                                Text("My Messages")
                            }
                        } else {
                            Text(
                                text = "Guest mode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onOpenSettings) {
                            Text("Settings")
                        }
                    }
                    Text("Tap a bottle to view a message", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (isSignedIn && syncError.isNullOrBlank()) {
                            "Cloud connected"
                        } else {
                            "Cloud not connected"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isGuest) {
                        Text(
                            text = "Guests can browse the map only. Writing, voting, and deleting require an account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!syncError.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = syncError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::clearSyncError) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            popupMessage?.let { message ->
                val canReadMessage = viewModel.canReadMessage(message)
                val canVoteOnMessage = viewModel.canVoteOnMessage(message)
                val canDeleteFromMap = viewModel.canDeleteMessageFromMap(message)
                val accent = rememberMessageAccent(message.displayedPoints)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (popupMessageId == message.id) {
                                popupMessageId = null
                            }
                        },
                    border = accent.border,
                    colors = CardDefaults.cardColors(
                        containerColor = accent.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MessageAuthorBadge(authorDisplayName = viewModel.displayAuthorUsername(message))
                        MessagePointsText(points = message.displayedPoints)
                        if (!canReadMessage) {
                            Text(
                                text = "Move within 150m of this pin to read and rate it.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(viewModel.displayMessageText(message), style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.upvoteMessage(message) },
                                enabled = !isGuest && canVoteOnMessage
                            ) {
                                Text("Upvote")
                            }
                            Button(
                                onClick = { viewModel.downvoteMessage(message) },
                                enabled = !isGuest && canVoteOnMessage
                            ) {
                                Text("Downvote")
                            }
                        }
                        if (isAdmin) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { viewModel.deleteMessage(message) },
                                    enabled = !isGuest && canDeleteFromMap
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                        Text("Tap card to close", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isWritingMessage) {
                        Button(
                            onClick = {
                                popupMessageId = null
                                isWritingMessage = true
                            },
                            enabled = !isGuest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Write a message")
                        }
                    } else {
                        val canWriteSelectedMessage = viewModel.canWriteSelectedMessage()
                        Text(
                            text = if (selectedLatLng == null) {
                                "Tap on the map to choose where to place your message."
                            } else {
                                "Location selected. You can save only if it is within 150m of you."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (selectedLatLng != null && !canWriteSelectedMessage) {
                            Text(
                                text = "Move closer to the selected point before saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = draftText,
                            onValueChange = viewModel::updateDraftText,
                            label = { Text("Message") },
                            enabled = !isGuest,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.saveMessage { didSave ->
                                        if (didSave) {
                                            isWritingMessage = false
                                        }
                                    }
                                },
                                enabled = !isGuest && draftText.isNotBlank() && selectedLatLng != null && canWriteSelectedMessage
                            ) {
                                Text("Save message")
                            }
                            Button(
                                onClick = {
                                    viewModel.clearSelectedLocation()
                                    isWritingMessage = false
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MyMessagesScreen(
    viewModel: MainViewModel = viewModel(),
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val myMessages by viewModel.myMessages.collectAsStateWithLifecycle()
    val myMessagesSortOrder by viewModel.myMessagesSortOrder.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("My Messages", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MyMessagesSortOrder.entries.forEach { sortOrder ->
                val isSelected = sortOrder == myMessagesSortOrder
                if (isSelected) {
                    Button(onClick = { viewModel.setMyMessagesSortOrder(sortOrder) }) {
                        Text(sortOrder.label)
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.setMyMessagesSortOrder(sortOrder) }) {
                        Text(sortOrder.label)
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(myMessages, key = { it.id }) { message ->
                val canReadMessage = viewModel.canReadOwnMessage(message)
                val canVoteOnMessage = viewModel.canVoteOnMessage(message)
                val accent = rememberMessageAccent(message.displayedPoints)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = accent.border,
                    colors = CardDefaults.cardColors(
                        containerColor = accent.containerColor
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MessageAuthorBadge(authorDisplayName = viewModel.displayAuthorUsername(message))
                        MessagePointsText(points = message.displayedPoints)
                        if (!canReadMessage) {
                            Text(
                                text = "Move within 150m of this pin to read and rate it.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(viewModel.displayMyMessageText(message), style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.upvoteMessage(message) },
                                enabled = !isGuest && canVoteOnMessage
                            ) {
                                Text("Upvote")
                            }
                            Button(
                                onClick = { viewModel.downvoteMessage(message) },
                                enabled = !isGuest && canVoteOnMessage
                            ) {
                                Text("Downvote")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = { viewModel.deleteMessage(message) },
                                enabled = !isGuest
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MessageAccent(
    val glowColor: Color?,
    val containerColor: Color,
    val border: BorderStroke?
)

@Composable
private fun rememberMessageAccent(points: Long): MessageAccent {
    val colorScheme = MaterialTheme.colorScheme
    val glowColor = when {
        points >= 100L -> Color(0xFFFF3B30)
        points >= 50L -> Color(0xFFFF9500)
        points >= 10L -> Color(0xFFFFD60A)
        else -> null
    }

    return remember(points, colorScheme.surfaceVariant, glowColor) {
        val baseContainer = colorScheme.surfaceVariant
        if (glowColor == null) {
            MessageAccent(
                glowColor = null,
                containerColor = baseContainer,
                border = null
            )
        } else {
            val borderAlpha = when {
                points >= 100L -> 0.9f
                points >= 50L -> 0.8f
                else -> 0.7f
            }
            val containerAlpha = when {
                points >= 100L -> 0.22f
                points >= 50L -> 0.18f
                else -> 0.12f
            }
            val borderWidth = when {
                points >= 100L -> 3.dp
                points >= 50L -> 2.dp
                else -> 1.dp
            }
            MessageAccent(
                glowColor = glowColor,
                containerColor = glowColor.copy(alpha = containerAlpha).compositeOver(baseContainer),
                border = BorderStroke(borderWidth, glowColor.copy(alpha = borderAlpha))
            )
        }
    }
}

@Composable
private fun MessagePointsText(points: Long) {
    val accent = rememberMessageAccent(points)
    val glowColor = accent.glowColor
    val textStyle = if (glowColor == null) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodyMedium.copy(
            color = glowColor,
            shadow = Shadow(
                color = glowColor.copy(alpha = 0.9f),
                offset = Offset.Zero,
                blurRadius = when {
                    points >= 100L -> 28f
                    points >= 50L -> 22f
                    else -> 16f
                }
            )
        )
    }

    Text(
        text = "Points: $points",
        style = textStyle
    )
}

@Composable
private fun rememberMessageMapMarkerIcons(): MessageMapMarkerIcons {
    val context = LocalContext.current
    val densityDpi = context.resources.configuration.densityDpi
    return remember(densityDpi) {
        MessageMapMarkerIcons(
            unread = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_unread, 44f),
            read = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_read, 44f),
            unreadHighlighted = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_unread, 52f),
            readHighlighted = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_read, 52f)
        )
    }
}

@Composable
private fun SettingsScreen(
    darkThemeEnabled: Boolean,
    profanityFilterEnabled: Boolean,
    profanityDisableWarningAcknowledged: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onProfanityFilterChanged: (Boolean) -> Unit,
    onAcknowledgeProfanityDisableWarning: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var showProfanityDisableDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Dark theme", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = darkThemeEnabled,
                onCheckedChange = onThemeChanged
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Profanity filter", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = profanityFilterEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        onProfanityFilterChanged(true)
                    } else if (!profanityDisableWarningAcknowledged) {
                        showProfanityDisableDialog = true
                    } else {
                        onProfanityFilterChanged(false)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Log out")
        }
    }

    if (showProfanityDisableDialog) {
        AlertDialog(
            onDismissRequest = { showProfanityDisableDialog = false },
            title = { Text("Turn Off Profanity Filter?") },
            text = {
                Text("This may reveal offensive language in messages. Are you sure you want to turn the filter off?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAcknowledgeProfanityDisableWarning()
                        onProfanityFilterChanged(false)
                        showProfanityDisableDialog = false
                    }
                ) {
                    Text("Turn off")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfanityDisableDialog = false }) {
                    Text("Keep on")
                }
            }
        )
    }
}

@Composable
private fun MapSection(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState,
    messages: List<LocationMessage>,
    selectedLatLng: LatLng?,
    showMessageMarkers: Boolean,
    nearbyMessageIds: Set<String>,
    readMessageIds: Set<String>,
    highlightedMessageId: String?,
    markerIcons: MessageMapMarkerIcons,
    isMyLocationEnabled: Boolean,
    onMapClick: (LatLng) -> Unit,
    onMarkerClick: (LocationMessage) -> Unit
) {
    val lockedUiSettings = remember {
        MapUiSettings(
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = true,
            tiltGesturesEnabled = false,
            rotationGesturesEnabled = false,
            zoomControlsEnabled = true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false
        )
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            isMyLocationEnabled = isMyLocationEnabled,
            minZoomPreference = 16f
        ),
        uiSettings = lockedUiSettings,
        onMapClick = onMapClick
    ) {
        if (showMessageMarkers) {
            messages.forEach { message ->
                val isNearby = nearbyMessageIds.contains(message.id)
                val isHighlighted = message.id == highlightedMessageId
                val markerTitle = if (isNearby) "Nearby" else "Message"
                val authorSnippet = message.authorUsername.trim().ifBlank { "Unknown user" }
                val hasBeenOpened = readMessageIds.contains(message.id)
                val markerIcon = if (!isNearby) {
                    BitmapDescriptorFactory.defaultMarker(
                        if (isHighlighted) BitmapDescriptorFactory.HUE_AZURE else BitmapDescriptorFactory.HUE_RED
                    )
                } else {
                    when {
                        isHighlighted && hasBeenOpened -> markerIcons.readHighlighted
                        isHighlighted -> markerIcons.unreadHighlighted
                        hasBeenOpened -> markerIcons.read
                        else -> markerIcons.unread
                    }
                }
                Marker(
                    state = MarkerState(position = LatLng(message.latitude, message.longitude)),
                    title = markerTitle,
                    snippet = authorSnippet,
                    icon = markerIcon,
                    zIndex = if (isHighlighted) 1f else 0f,
                    onClick = {
                        onMarkerClick(message)
                        true
                    }
                )
            }
        }
        selectedLatLng?.let {
            Marker(state = MarkerState(position = it), title = "Selected location")
        }
    }
}

private const val AUTH_USERNAME_PREVIEW_MIN_LENGTH = 2

@Composable
private fun MessageAuthorBadge(authorDisplayName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "From",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
            Text(
                text = authorDisplayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private enum class AppDestination {
    MAIN,
    SETTINGS,
    MY_MESSAGES
}

private enum class AuthDestination {
    HOME,
    LOGIN,
    REGISTER,
    FORGOT_PASSWORD
}

private const val PASSWORD_REQUIREMENT_MIN_LENGTH = 8

private fun isPasswordValid(password: String): Boolean {
    return password.length >= PASSWORD_REQUIREMENT_MIN_LENGTH &&
        password.any { it.isUpperCase() } &&
        password.any { it.isLowerCase() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() }
}

@SuppressLint("MissingPermission")
@Composable
private fun LocationUpdatesEffect(onLocationUpdate: (Location) -> Unit) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    DisposableEffect(Unit) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let(onLocationUpdate)
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocationUpdate)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, callback, context.mainLooper)

        onDispose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

package com.example.dailytasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        setContent {
            val viewModel: MainViewModel = viewModel()
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

    BackHandler(enabled = authDestination != AuthDestination.HOME) {
        authDestination = AuthDestination.HOME
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
            text = "Message In A Bottle",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create an account, sign in, or continue as a guest.",
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
                        if (isBusy || !passwordsMatch) return@Button
                        isBusy = true
                        viewModel.registerWithEmailPassword(email, password) { ok ->
                            isBusy = false
                            if (ok) onAuthenticated()
                        }
                    },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank() && passwordsMatch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Please wait..." else "Create account")
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
        Spacer(modifier = Modifier.height(24.dp))
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
        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(user, 16f))
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
                                Text("Your messages")
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
                    Text("Tap a pin on the map to see its message.", style = MaterialTheme.typography.titleMedium)
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (popupMessageId == message.id) {
                                popupMessageId = null
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Points: ${message.displayedPoints}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(viewModel.displayMessageText(message), style = MaterialTheme.typography.bodyLarge)
                        if (!canReadMessage) {
                            Text(
                                text = "Move within 150m of this pin to read and rate it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                            Text("Write message")
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
            Text("Your messages", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(myMessages, key = { it.id }) { message ->
                val canReadMessage = viewModel.canReadOwnMessage(message)
                val canVoteOnMessage = viewModel.canVoteOnOwnMessage(message)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Points: ${message.displayedPoints}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(viewModel.displayMyMessageText(message), style = MaterialTheme.typography.bodyLarge)
                        if (!canReadMessage) {
                            Text(
                                text = "Move within 150m of this pin to read and rate it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
        properties = MapProperties(isMyLocationEnabled = isMyLocationEnabled),
        uiSettings = lockedUiSettings,
        onMapClick = onMapClick
    ) {
        if (showMessageMarkers) {
            messages.forEach { message ->
                val isNearby = nearbyMessageIds.contains(message.id)
                val isHighlighted = message.id == highlightedMessageId
                val markerTitle = if (isNearby) "Nearby" else "Message"
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
                    snippet = null,
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

private enum class AppDestination {
    MAIN,
    SETTINGS,
    MY_MESSAGES
}

private enum class AuthDestination {
    HOME,
    LOGIN,
    REGISTER
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

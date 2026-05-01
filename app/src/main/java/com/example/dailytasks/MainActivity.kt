package com.example.dailytasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val darkThemeEnabled by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
            var enteredMainApp by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = if (darkThemeEnabled) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!enteredMainApp) {
                        LoginScreen(
                            viewModel = viewModel,
                            onGuestContinue = { enteredMainApp = true }
                        )
                    } else {
                        AppScreen(
                            viewModel = viewModel,
                            onLogout = { enteredMainApp = false }
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
    onGuestContinue: () -> Unit
) {
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    var isBusy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
            text = "Map Messages",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create an account, sign in, or continue as a guest.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isBusy) return@Button
                    isBusy = true
                    viewModel.registerWithEmailPassword(email, password) { ok ->
                        isBusy = false
                        if (ok) onGuestContinue()
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Register")
            }
            Button(
                onClick = {
                    if (isBusy) return@Button
                    isBusy = true
                    viewModel.signInWithEmailPassword(email, password) { ok ->
                        isBusy = false
                        if (ok) onGuestContinue()
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Log in")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (isBusy) return@Button
                isBusy = true
                viewModel.signInAsGuest { ok ->
                    isBusy = false
                    if (ok) onGuestContinue()
                }
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isBusy) "Please wait…" else "Guest login")
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
                onThemeChanged = viewModel::setDarkThemeEnabled,
                onProfanityFilterChanged = viewModel::setProfanityFilterEnabled,
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
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val selectedLatLng by viewModel.selectedLatLng.collectAsStateWithLifecycle()
    val userLatLng by viewModel.userLatLng.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    var permissionsGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val cameraPositionState = rememberCameraPositionState()
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var popupMessageId by remember { mutableStateOf<String?>(null) }
    var isWritingMessage by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
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
        if (!hasCenteredOnUser) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(user, 16f))
            hasCenteredOnUser = true
        }
    }

    if (permissionsGranted) {
        LocationUpdatesEffect(onLocationUpdate = viewModel::updateUserLocation)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapSection(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            messages = messages,
            selectedLatLng = selectedLatLng,
            nearbyMessageIds = nearbyMessages.map { it.id }.toSet(),
            highlightedMessageId = popupMessageId,
            isMyLocationEnabled = permissionsGranted,
            onMapClick = {
                popupMessageId = null
                if (isWritingMessage) {
                    viewModel.updateSelectedLocation(it)
                }
            },
            onMarkerClick = { message ->
                popupMessageId = message.id
                coroutineScope.launch {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(message.latitude, message.longitude),
                            17f
                        )
                    )
                }
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
                    Text("Explore the map freely, then press Write message when you're ready.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (isSignedIn) "Cloud sync connected" else "Connecting to cloud sync...",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isGuest) {
                        Text(
                            text = "Guests can browse, but can’t write messages or vote.",
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.upvoteMessage(message) },
                                enabled = !isGuest
                            ) {
                                Text("Upvote")
                            }
                            Button(
                                onClick = { viewModel.downvoteMessage(message) },
                                enabled = !isGuest
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
                        Text(
                            text = if (selectedLatLng == null) {
                                "Tap the map to choose where to place your message."
                            } else {
                                "Location selected. Add your message and save it."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OutlinedTextField(
                            value = draftText,
                            onValueChange = viewModel::updateDraftText,
                            label = { Text("Message") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.saveMessage()
                                    isWritingMessage = false
                                },
                                enabled = !isGuest && draftText.isNotBlank() && selectedLatLng != null
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
    val myMessages by viewModel.myMessages.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Points: ${message.displayedPoints}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(viewModel.displayMessageText(message), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.upvoteMessage(message) },
                                enabled = !isGuest
                            ) {
                                Text("Upvote")
                            }
                            Button(
                                onClick = { viewModel.downvoteMessage(message) },
                                enabled = !isGuest
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
private fun SettingsScreen(
    darkThemeEnabled: Boolean,
    profanityFilterEnabled: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onProfanityFilterChanged: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                onCheckedChange = onProfanityFilterChanged
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
}

@Composable
private fun MapSection(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState,
    messages: List<LocationMessage>,
    selectedLatLng: LatLng?,
    nearbyMessageIds: Set<String>,
    highlightedMessageId: String?,
    isMyLocationEnabled: Boolean,
    onMapClick: (LatLng) -> Unit,
    onMarkerClick: (LocationMessage) -> Unit
) {
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = isMyLocationEnabled),
        onMapClick = onMapClick
    ) {
        messages.forEach { message ->
            val markerTitle = if (nearbyMessageIds.contains(message.id)) "Nearby" else "Message"
            Marker(
                state = MarkerState(position = LatLng(message.latitude, message.longitude)),
                title = markerTitle,
                snippet = null,
                icon = BitmapDescriptorFactory.defaultMarker(
                    if (message.id == highlightedMessageId) {
                        BitmapDescriptorFactory.HUE_AZURE
                    } else {
                        BitmapDescriptorFactory.HUE_RED
                    }
                ),
                onClick = {
                    onMarkerClick(message)
                    true
                }
            )
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

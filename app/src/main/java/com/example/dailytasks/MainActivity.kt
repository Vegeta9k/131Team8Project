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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            MaterialTheme(colorScheme = if (darkThemeEnabled) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppScreen(viewModel: MainViewModel) {
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
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    var permissionsGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val cameraPositionState = rememberCameraPositionState()
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    val expandedNearbyMessages = remember { mutableStateMapOf<String, Boolean>() }
    var openMessageId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

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
            TextButton(onClick = onOpenMyMessages) {
                Text("Your messages")
            }
            TextButton(onClick = onOpenSettings) {
                Text("Settings")
            }
        }

        Text("Tap map to choose where to place a message", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (isSignedIn) "Cloud sync connected" else "Connecting to cloud sync...",
            style = MaterialTheme.typography.bodySmall
        )
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

        MapSection(
            cameraPositionState = cameraPositionState,
            messages = messages,
            selectedLatLng = selectedLatLng,
            nearbyMessageIds = nearbyMessages.map { it.id }.toSet(),
            highlightedMessageId = openMessageId,
            isMyLocationEnabled = permissionsGranted,
            onMapClick = viewModel::updateSelectedLocation
        )

        OutlinedTextField(
            value = draftText,
            onValueChange = viewModel::updateDraftText,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::saveMessage,
                enabled = draftText.isNotBlank() && (selectedLatLng != null || userLatLng != null)
            ) {
                Text("Save message")
            }
            Button(onClick = viewModel::clearSelectedLocation) {
                Text("Clear selection")
            }
        }

        Text(
            text = "Nearby messages (within ${MainViewModel.NEARBY_RADIUS_METERS.toInt()}m)",
            style = MaterialTheme.typography.titleSmall
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nearbyMessages, key = { it.id }) { message ->
                val isExpanded = expandedNearbyMessages[message.id] == true
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val shouldExpand = !isExpanded
                            expandedNearbyMessages[message.id] = shouldExpand
                            if (shouldExpand) {
                                openMessageId = message.id
                                coroutineScope.launch {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(
                                            LatLng(message.latitude, message.longitude),
                                            17f
                                        )
                                    )
                                }
                            } else if (openMessageId == message.id) {
                                openMessageId = null
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Points: ${message.displayedPoints}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isExpanded) {
                            Text(viewModel.displayMessageText(message), style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.upvoteMessage(message) }) {
                                    Text("Upvote")
                                }
                                Button(onClick = { viewModel.downvoteMessage(message) }) {
                                    Text("Downvote")
                                }
                            }
                            Text("Tap to hide", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Hidden message", style = MaterialTheme.typography.bodyLarge)
                            Text("Tap to open", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyMessagesScreen(
    viewModel: MainViewModel = viewModel(),
    onBack: () -> Unit
) {
    val myMessages by viewModel.myMessages.collectAsStateWithLifecycle()

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
                            Button(onClick = { viewModel.upvoteMessage(message) }) {
                                Text("Upvote")
                            }
                            Button(onClick = { viewModel.downvoteMessage(message) }) {
                                Text("Downvote")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.deleteMessage(message) }) {
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
    }
}

@Composable
private fun MapSection(
    cameraPositionState: CameraPositionState,
    messages: List<LocationMessage>,
    selectedLatLng: LatLng?,
    nearbyMessageIds: Set<String>,
    highlightedMessageId: String?,
    isMyLocationEnabled: Boolean,
    onMapClick: (LatLng) -> Unit
) {
    GoogleMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
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
                )
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

package com.example.dailytasks

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PANEL_CONTAINER_ALPHA = 0.94f
private const val HEADER_CONTAINER_ALPHA = 0.97f
private const val MESSAGE_CARD_BASE_ALPHA = 0.88f

private val AppPanelShape = RoundedCornerShape(20.dp)
private val ControlShape = RoundedCornerShape(12.dp)

private data class MessageMapMarkerIcons(
    val unread: BitmapDescriptor,
    val read: BitmapDescriptor,
    val unreadHighlighted: BitmapDescriptor,
    val readHighlighted: BitmapDescriptor,
    val unreadOutOfRange: BitmapDescriptor,
    val readOutOfRange: BitmapDescriptor,
    val unreadOutOfRangeHighlighted: BitmapDescriptor,
    val readOutOfRangeHighlighted: BitmapDescriptor
)

// Converts drawable resources into map marker icons, with optional fading/desaturation for state changes.
private fun bitmapDescriptorFromDrawable(
    context: Context,
    @DrawableRes resId: Int,
    targetWidthDp: Float = 44f,
    alpha: Float = 1f,
    saturation: Float = 1f
): BitmapDescriptor {
    val drawable = ContextCompat.getDrawable(context, resId)!!
    val density = context.resources.displayMetrics.density
    val targetPx = (targetWidthDp * density).toInt().coerceIn(24, 256)
    val srcW = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetPx
    val srcH = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetPx
    val aspect = srcH.toFloat() / srcW.toFloat().coerceAtLeast(0.01f)
    val w = targetPx
    val h = (targetPx * aspect).toInt().coerceAtLeast(1).coerceAtMost(512)
    val sourceBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val sourceCanvas = Canvas(sourceBitmap)
    drawable.setBounds(0, 0, sourceCanvas.width, sourceCanvas.height)
    drawable.draw(sourceCanvas)
    val bitmap = if (alpha < 1f || saturation < 1f) {
        val adjustedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val adjustedCanvas = Canvas(adjustedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            if (saturation < 1f) {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 1f)) }
                )
            }
        }
        adjustedCanvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
        adjustedBitmap
    } else {
        sourceBitmap
    }
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Entry point for the app.
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Maps and upvote notifications.
        MapsInitializer.initialize(this)
        UpvoteNotifier.createChannel(this)

        setContent {
            AppRoot()
        }
    }
}

// Root Compose switchboard.
// It decides whether the user sees the startup spinner, the auth flow, or the signed-in app shell
// based on auth state exposed by MainViewModel.
@Composable
private fun AppRoot(viewModel: MainViewModel = viewModel()) {
    NotificationPermissionEffect()

    val darkThemeEnabled by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
    val authStateResolved by viewModel.authStateResolved.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
    val shouldEnterMainApp = isSignedIn || isGuest

    MaterialTheme(colorScheme = if (darkThemeEnabled) darkColorScheme() else lightColorScheme()) {
        VisibleSystemBarsEffect(darkThemeEnabled = darkThemeEnabled)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !authStateResolved -> LoadingScreen()
                    !shouldEnterMainApp -> LoginScreen(viewModel = viewModel, onAuthenticated = {})
                    else -> AppScreen(viewModel = viewModel, onLogout = {})
                }
                StatusBarBackdrop(modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun StatusBarBackdrop(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surface.copy(alpha = HEADER_CONTAINER_ALPHA),
        shadowElevation = 4.dp
    ) {}
}

// Keeps the app content behind the system bars while updating icon colors for light/dark themes.
@Composable
private fun VisibleSystemBarsEffect(darkThemeEnabled: Boolean) {
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme

    DisposableEffect(view, colorScheme.surface, darkThemeEnabled) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkThemeEnabled
                isAppearanceLightNavigationBars = !darkThemeEnabled
            }
        }
        onDispose { }
    }
}

// Requests notification permission on Android 13+.
@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

// Shared elevated surface used by non-map panels for a consistent app shell.
@Composable
private fun AppPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppPanelShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_CONTAINER_ALPHA)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

// Default profile mark reused in headers and account surfaces.
@Composable
private fun QuillAvatar(
    size: Dp,
    iconSize: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.ic_profile_quill),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

// Standard top row for screens that need a title and a back action.
@Composable
private fun ScreenHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

// Authentication landing page plus login, registration, and password reset forms.
@Composable
private fun LoginScreen(
    viewModel: MainViewModel,
    onAuthenticated: () -> Unit
) {
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    // Temporary form state and auth-screen routing live locally because they only matter while
    // this composable is on screen.
    var authDestination by remember { mutableStateOf(AuthDestination.HOME) }
    var isBusy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    fun navigateBackInAuth() {
        when (authDestination) {
            AuthDestination.FORGOT_PASSWORD -> authDestination = AuthDestination.LOGIN
            else -> authDestination = AuthDestination.HOME
        }
        viewModel.clearSyncError()
    }

    BackHandler(enabled = authDestination != AuthDestination.HOME) {
        navigateBackInAuth()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (authDestination != AuthDestination.HOME) {
            AuthBackButton(
                onClick = ::navigateBackInAuth,
                enabled = !isBusy,
                modifier = Modifier.align(Alignment.Start)
            )
        } else {
            Spacer(modifier = Modifier.height(48.dp))
        }

        AppPanel {
            AuthHeader()
            Spacer(modifier = Modifier.height(8.dp))

            // This screen swaps form bodies in place instead of navigating to separate destinations.
            when (authDestination) {
                AuthDestination.HOME -> {
                    Button(
                        onClick = {
                            viewModel.clearSyncError()
                            authDestination = AuthDestination.REGISTER
                        },
                        enabled = !isBusy,
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Register")
                    }
                    Button(
                        onClick = {
                            viewModel.clearSyncError()
                            authDestination = AuthDestination.LOGIN
                        },
                        enabled = !isBusy,
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Log in")
                    }
                    OutlinedButton(
                        onClick = {
                            if (isBusy) return@OutlinedButton
                            isBusy = true
                            viewModel.signInAsGuest { ok ->
                                isBusy = false
                                if (ok) onAuthenticated()
                            }
                        },
                        enabled = !isBusy,
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Please wait..." else "Guest login")
                    }
                }

                AuthDestination.LOGIN -> {
                    // Existing members authenticate here with Firebase email/password credentials.
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Email") },
                        singleLine = true,
                        shape = ControlShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        shape = ControlShape,
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
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Please wait..." else "Log in")
                    }
                }

                AuthDestination.REGISTER -> {
                    // Registration collects both auth credentials and the public username stored
                    // alongside messages in Firestore.
                    val passwordsMatch = confirmPassword == password
                    val passwordValid = isPasswordValid(password)

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
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Email") },
                        singleLine = true,
                        shape = ControlShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        shape = ControlShape,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        shape = ControlShape,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PasswordRequirements(password = password)
                    if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                        Text(
                            text = "Passwords do not match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    }
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
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Please wait..." else "Create account")
                    }
                }

                AuthDestination.FORGOT_PASSWORD -> {
                    // Password recovery delegates the actual email delivery to Firebase Auth.
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            viewModel.clearSyncError()
                        },
                        label = { Text("Email") },
                        singleLine = true,
                        shape = ControlShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
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
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Sending..." else "Send Password Reset Email")
                    }
                }
            }

            if (!syncError.isNullOrBlank()) {
                Text(
                    text = syncError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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

// Header used on the login and registration screens.
@Composable
private fun AuthHeader() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        QuillAvatar(size = 64.dp, iconSize = 38.dp, cornerRadius = 20.dp)
    }
    Text(
        text = "Message In a Bottle",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Please continue to register, login, or guest login below.",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// Small back button used while moving between auth sub-screens.
@Composable
private fun AuthBackButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(
            text = "<",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

// Shows the password rules one at a time.
@Composable
private fun PasswordRequirements(password: String) {
    val requirements = listOf(
        PasswordRequirement(
            label = "At least $PASSWORD_REQUIREMENT_MIN_LENGTH characters",
            isMet = password.length >= PASSWORD_REQUIREMENT_MIN_LENGTH
        ),
        PasswordRequirement(
            label = "One uppercase letter",
            isMet = password.any { it.isUpperCase() }
        ),
        PasswordRequirement(
            label = "One lowercase letter",
            isMet = password.any { it.isLowerCase() }
        ),
        PasswordRequirement(
            label = "One number",
            isMet = password.any { it.isDigit() }
        ),
        PasswordRequirement(
            label = "One special character",
            isMet = password.any { !it.isLetterOrDigit() }
        )
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Password requirements:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )
        requirements.forEach { requirement ->
            PasswordRequirementRow(
                requirement = requirement,
                passwordHasInput = password.isNotEmpty()
            )
        }
    }
}

@Composable
private fun PasswordRequirementRow(
    requirement: PasswordRequirement,
    passwordHasInput: Boolean
) {
    val rowColor = when {
        !passwordHasInput -> MaterialTheme.colorScheme.onSurfaceVariant
        requirement.isMet -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val marker = if (requirement.isMet) "OK" else "-"

    Text(
        text = "$marker ${requirement.label}",
        style = MaterialTheme.typography.bodySmall,
        color = rowColor
    )
}

private data class PasswordRequirement(
    val label: String,
    val isMet: Boolean
)

// Hosts the authenticated part of the app with lightweight in-memory navigation.
@Composable
private fun AppScreen(
    viewModel: MainViewModel,
    onLogout: () -> Unit
) {
    var destination by remember { mutableStateOf(AppDestination.MAIN) }

    // Simple in-memory navigation for the authenticated part of the app.
    // Since all authenticated screens share one activity and one ViewModel, switching on an enum
    // is enough here instead of introducing a full navigation graph.
    when (destination) {
        AppDestination.MAIN -> {
            LocationMessagesScreen(
                viewModel = viewModel,
                onOpenSettings = { destination = AppDestination.SETTINGS },
                onOpenMyMessages = { destination = AppDestination.MY_MESSAGES },
                onOpenAccount = { destination = AppDestination.ACCOUNT }
            )
        }
        AppDestination.ACCOUNT -> {
            AccountScreen(
                viewModel = viewModel,
                onBack = { destination = AppDestination.MAIN }
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

// Main signed-in screen.
// This composable pulls together live message state, location permission handling, map camera
// behavior, marker selection, and the bottom composer used to create a new pin.
@Composable
private fun LocationMessagesScreen(
    viewModel: MainViewModel = viewModel(),
    onOpenSettings: () -> Unit,
    onOpenMyMessages: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val context = LocalContext.current
    // Shared app state from the ViewModel. These values control what the map shows and what the
    // user is currently allowed to do.
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val nearbyMessages by viewModel.nearbyMessages.collectAsStateWithLifecycle()
    val readMessageIds by viewModel.readMessageIds.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val selectedLatLng by viewModel.selectedLatLng.collectAsStateWithLifecycle()
    val userLatLng by viewModel.userLatLng.collectAsStateWithLifecycle()
    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val currentUsername by viewModel.currentUsername.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    // Purely local UI state for this screen.
    var permissionsGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val cameraPositionState = rememberCameraPositionState()
    var popupMessageId by remember { mutableStateOf<String?>(null) }
    var isWritingMessage by remember { mutableStateOf(false) }
    val popupMessage = messages.firstOrNull { it.id == popupMessageId }

    // Android permission launcher for fine/coarse location.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Prompt once for location if it is missing so the map can center on the user and enforce
    // nearby-only reading/writing rules.
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

    // Recenter the camera whenever a fresh user location arrives.
    // Right now this always animates to the latest fix, which keeps the user centered.
    LaunchedEffect(userLatLng) {
        val user = userLatLng ?: return@LaunchedEffect
        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(user, 18f))
    }

    // Only request continuous GPS updates after location access has been granted.
    if (permissionsGranted) {
        LocationUpdatesEffect(onLocationUpdate = viewModel::updateUserLocation)
    }

    // Build marker artwork once and reuse it across recompositions.
    val mapMarkerIcons = rememberMessageMapMarkerIcons()

    Box(modifier = Modifier.fillMaxSize()) {
        // The map is the base layer for this whole screen.
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
                // In write mode, a map tap chooses the target point for the new message.
                if (isWritingMessage && !isGuest) {
                    viewModel.updateSelectedLocation(it)
                }
            },
            onMarkerClick = { message ->
                // Opening a nearby pin marks it as read so future markers/cards can reflect that.
                popupMessageId = message.id
                viewModel.markMessageReadIfNearby(message)
            }
        )

        // Header overlay pinned to the top of the map.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp)
        ) {
            MapHeaderBlock(
                isGuest = isGuest,
                username = currentUsername,
                syncError = syncError,
                onOpenMyMessages = onOpenMyMessages,
                onOpenSettings = onOpenSettings,
                onOpenAccount = onOpenAccount,
                onDismissSyncError = viewModel::clearSyncError
            )
        }

        // Bottom overlay used for the selected-message popup and the write-message composer.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // When a marker is selected, show a compact card above the composer instead of opening
            // a separate screen or bottom sheet.
            popupMessage?.let { message ->
                val canReadMessage = viewModel.canReadMessage(message)
                val canVoteOnMessage = viewModel.canVoteOnMessage(message)
                val canDeleteFromMap = viewModel.canDeleteMessageFromMap(message)
                MessageCard(
                    message = message,
                    authorDisplayName = viewModel.displayAuthorUsername(message),
                    visibleText = viewModel.displayMessageText(message),
                    canRead = canReadMessage,
                    canVote = !isGuest && canVoteOnMessage,
                    canDelete = !isGuest && canDeleteFromMap,
                    showDeleteButton = isAdmin,
                    onUpvote = { viewModel.upvoteMessage(message) },
                    onDownvote = { viewModel.downvoteMessage(message) },
                    onDelete = { viewModel.deleteMessage(message) },
                    footerText = "Tap card to close",
                    compact = true,
                    modifier = Modifier.clickable {
                        // Let the card itself dismiss the popup for a quick close gesture.
                        if (popupMessageId == message.id) {
                            popupMessageId = null
                        }
                    }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppPanelShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_CONTAINER_ALPHA)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isWritingMessage) {
                        Button(
                            onClick = {
                                // Enter compose mode and free up map taps for location selection.
                                popupMessageId = null
                                isWritingMessage = true
                            },
                            enabled = !isGuest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Write a message")
                        }
                    } else {
                        // Write mode is a two-step flow:
                        // 1. tap the map to choose a pin location
                        // 2. enter text and save if the point is close enough
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
                                        // Collapse the composer only after the repository confirms
                                        // the write succeeded.
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
                                    // Cancel exits compose mode and forgets the temporary map point.
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

// Floating map header with profile navigation and quick actions.
// Top summary panel above the map showing identity, navigation, and current guidance/errors.
@Composable
private fun MapHeaderBlock(
    isGuest: Boolean,
    username: String,
    syncError: String?,
    onOpenMyMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onDismissSyncError: () -> Unit
) {
    val displayName = if (isGuest) {
        "Guest"
    } else {
        username.trim().ifBlank { "Signed in" }
    }
    val subtitle = if (isGuest) "Browse only" else "Account"
    val instructionText = if (isGuest) {
        "Tap a bottle to view a message. Writing and voting require an account."
    } else {
        "Tap a bottle to view a message."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppPanelShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = HEADER_CONTAINER_ALPHA),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The profile preview on the left doubles as navigation into the account screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenAccount),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuillAvatar(size = 44.dp, iconSize = 28.dp, cornerRadius = 14.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (!isGuest) {
                        TextButton(onClick = onOpenMyMessages) {
                            Text("Messages")
                        }
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            }

            Text(
                text = instructionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!syncError.isNullOrBlank()) {
                // Repository/auth errors surface here so the user can react without leaving the map.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = syncError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissSyncError) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

// Read-only account details assembled from current ViewModel state.
@Composable
private fun AccountScreen(
    viewModel: MainViewModel = viewModel(),
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val isGuest by viewModel.isGuest.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val username by viewModel.currentUsername.collectAsStateWithLifecycle()
    val email by viewModel.currentUserEmail.collectAsStateWithLifecycle()
    val userId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val myMessages by viewModel.myMessages.collectAsStateWithLifecycle()

    val displayName = if (isGuest) {
        "Guest"
    } else {
        username.trim().ifBlank { "Signed in" }
    }
    val accountType = when {
        isGuest -> "Guest"
        isAdmin -> "Admin"
        else -> "Registered"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(title = "Account", onBack = onBack)

        AppPanel {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuillAvatar(size = 56.dp, iconSize = 34.dp, cornerRadius = 18.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = accountType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AccountInfoRow(label = "Username", value = displayName)
            AccountInfoRow(
                label = "Email",
                value = if (isGuest) "Guest accounts do not have an email." else email.ifBlank { "Unavailable" }
            )
            AccountInfoRow(label = "Messages posted", value = myMessages.size.toString())
            AccountInfoRow(label = "Permissions", value = if (isAdmin) "Admin controls enabled" else "Standard access")
            AccountInfoRow(label = "User ID", value = userId.orEmpty().ifBlank { "Not available" })
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// User-authored messages list with local sorting controls.
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
        ScreenHeader(title = "Messages", onBack = onBack)

        AppPanel {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MyMessagesSortOrder.entries.forEach { sortOrder ->
                    val isSelected = sortOrder == myMessagesSortOrder
                    if (isSelected) {
                        Button(
                            onClick = { viewModel.setMyMessagesSortOrder(sortOrder) },
                            shape = ControlShape
                        ) {
                            Text(sortOrder.label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setMyMessagesSortOrder(sortOrder) },
                            shape = ControlShape
                        ) {
                            Text(sortOrder.label)
                        }
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(myMessages, key = { it.id }) { message ->
                val canReadMessage = viewModel.canReadOwnMessage(message)
                val canVoteOnMessage = viewModel.canVoteOnMessage(message)
                MessageCard(
                    message = message,
                    authorDisplayName = viewModel.displayAuthorUsername(message),
                    visibleText = viewModel.displayMyMessageText(message),
                    canRead = canReadMessage,
                    canVote = !isGuest && canVoteOnMessage,
                    canDelete = !isGuest,
                    onUpvote = { viewModel.upvoteMessage(message) },
                    onDownvote = { viewModel.downvoteMessage(message) },
                    onDelete = { viewModel.deleteMessage(message) }
                )
            }
        }
    }
}

// Shared card for map popups and the Messages screen.
@Composable
private fun MessageCard(
    message: LocationMessage,
    authorDisplayName: String,
    visibleText: String,
    canRead: Boolean,
    canVote: Boolean,
    canDelete: Boolean,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit,
    onDelete: () -> Unit,
    showDeleteButton: Boolean = canDelete,
    modifier: Modifier = Modifier,
    footerText: String? = null,
    compact: Boolean = false
) {
    val accent = rememberMessageAccent(message.displayedPoints)
    val cardPadding = if (compact) 8.dp else 12.dp
    val itemSpacing = if (compact) 6.dp else 10.dp
    val messageTextStyle = if (compact) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodyLarge
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppPanelShape,
        border = accent.border,
        colors = CardDefaults.cardColors(containerColor = accent.containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            // This reusable card renders both the map popup and each row in "My Messages".
            MessageCardHeader(
                authorDisplayName = authorDisplayName,
                postedDate = formatPostedDate(message.createdAtEpochMs),
                points = message.displayedPoints,
                compact = compact
            )

            Text(
                // When the user is too far away, the real text is hidden by the ViewModel's rule.
                text = if (canRead) visibleText else "Move within 150m of this pin to read and rate it.",
                style = messageTextStyle,
                color = if (canRead) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            MessageActionsRow(
                canVote = canVote,
                showDeleteButton = showDeleteButton,
                canDelete = canDelete,
                onUpvote = onUpvote,
                onDownvote = onDownvote,
                onDelete = onDelete
            )

            footerText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun MessageCardHeader(
    authorDisplayName: String,
    postedDate: String,
    points: Long,
    compact: Boolean
) {
    val accent = rememberMessageAccent(points)
    val pointsColor = accent.glowColor ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = authorDisplayName,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = postedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = pointsColor.copy(alpha = 0.12f)
        ) {
            Text(
                text = "$points pts",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = pointsColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MessageActionsRow(
    canVote: Boolean,
    showDeleteButton: Boolean,
    canDelete: Boolean,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onUpvote,
            enabled = canVote,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Upvote")
        }
        OutlinedButton(
            onClick = onDownvote,
            enabled = canVote,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Downvote")
        }
        if (showDeleteButton) {
            TextButton(
                onClick = onDelete,
                enabled = canDelete
            ) {
                Text("Delete")
            }
        }
    }
}

// Formats Firestore timestamps into a short, human-readable label for cards and popups.
private fun formatPostedDate(createdAtEpochMs: Long): String {
    if (createdAtEpochMs <= 0L) return "Unknown date"
    return SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(createdAtEpochMs))
}

private data class MessageAccent(
    val glowColor: Color?,
    val containerColor: Color,
    val border: BorderStroke?
)

// Computes the visual treatment for highly rated messages so they stand out in the UI.
@Composable
private fun rememberMessageAccent(points: Long): MessageAccent {
    val colorScheme = MaterialTheme.colorScheme
    val glowColor = when {
        points >= 100L -> Color(0xFFC9A227)
        points >= 50L -> Color(0xFFC56A00)
        points >= 25L -> Color(0xFF1E6FA8)
        points >= 10L -> Color(0xFF2E7D32)
        else -> null
    }

    return remember(points, colorScheme.surface, glowColor) {
        val baseContainer = colorScheme.surface.copy(alpha = MESSAGE_CARD_BASE_ALPHA)
        if (glowColor == null) {
            MessageAccent(
                glowColor = null,
                containerColor = baseContainer,
                border = null
            )
        } else {
            val borderAlpha = when {
                points >= 100L -> 0.9f
                points >= 50L -> 0.78f
                points >= 25L -> 0.72f
                else -> 0.45f
            }
            val containerAlpha = when {
                points >= 100L -> 0.22f
                points >= 50L -> 0.16f
                points >= 25L -> 0.14f
                else -> 0.08f
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

// Pre-renders marker drawables at the sizes and states needed by the map.
@Composable
private fun rememberMessageMapMarkerIcons(): MessageMapMarkerIcons {
    val context = LocalContext.current
    val densityDpi = context.resources.configuration.densityDpi
    return remember(densityDpi) {
        MessageMapMarkerIcons(
            unread = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_unread, 44f),
            read = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_read, 44f),
            unreadHighlighted = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_unread, 52f),
            readHighlighted = bitmapDescriptorFromDrawable(context, R.drawable.map_marker_message_read, 52f),
            unreadOutOfRange = bitmapDescriptorFromDrawable(
                context = context,
                resId = R.drawable.map_marker_message_unread,
                targetWidthDp = 44f,
                alpha = 0.6f,
                saturation = 0.2f
            ),
            readOutOfRange = bitmapDescriptorFromDrawable(
                context = context,
                resId = R.drawable.map_marker_message_read,
                targetWidthDp = 44f,
                alpha = 0.6f,
                saturation = 0.2f
            ),
            unreadOutOfRangeHighlighted = bitmapDescriptorFromDrawable(
                context = context,
                resId = R.drawable.map_marker_message_unread,
                targetWidthDp = 52f,
                alpha = 0.8f,
                saturation = 0.35f
            ),
            readOutOfRangeHighlighted = bitmapDescriptorFromDrawable(
                context = context,
                resId = R.drawable.map_marker_message_read,
                targetWidthDp = 52f,
                alpha = 0.8f,
                saturation = 0.35f
            )
        )
    }
}

// Settings page for appearance and content-filter preferences stored on the device.
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
        ScreenHeader(title = "Settings", onBack = onBack)

        AppPanel {
            SettingsToggleRow(
                title = "Dark theme",
                supportingText = "Use a darker color palette throughout the app.",
                checked = darkThemeEnabled,
                onCheckedChange = onThemeChanged
            )

            SettingsToggleRow(
                title = "Profanity filter",
                supportingText = "Hide offensive language in messages when possible.",
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

            Button(
                onClick = onLogout,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Log out")
            }
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
private fun SettingsToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// Wraps the Google Map and chooses the correct marker style for each message state.
// Marker appearance communicates three things at once: read/unread, nearby/out-of-range, and
// whether the pin is currently highlighted in the popup.
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
    // Intentionally limit map gestures so the screen behaves like a focused note map rather than
    // a fully open-ended maps app.
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
                val markerTitle = if (isNearby) "Nearby" else "Out of range"
                val authorSnippet = message.authorUsername.trim().ifBlank { "Unknown user" }
                val hasBeenOpened = readMessageIds.contains(message.id)
                // Pick one pre-rendered icon based on distance, selection, and read state.
                val markerIcon = when {
                    !isNearby && isHighlighted && hasBeenOpened -> markerIcons.readOutOfRangeHighlighted
                    !isNearby && isHighlighted -> markerIcons.unreadOutOfRangeHighlighted
                    !isNearby && hasBeenOpened -> markerIcons.readOutOfRange
                    !isNearby -> markerIcons.unreadOutOfRange
                    isHighlighted && hasBeenOpened -> markerIcons.readHighlighted
                    isHighlighted -> markerIcons.unreadHighlighted
                    hasBeenOpened -> markerIcons.read
                    else -> markerIcons.unread
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
            // Temporary marker shown while the user is composing a new message.
            Marker(state = MarkerState(position = it), title = "Selected location")
        }
    }
}

private const val AUTH_USERNAME_PREVIEW_MIN_LENGTH = 2

private enum class AppDestination {
    MAIN,
    ACCOUNT,
    SETTINGS,
    MY_MESSAGES
}

// Tracks which auth sub-screen the user is currently viewing.
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
// Requests continuous location updates while the map screen is visible.
// The effect subscribes on entry and automatically unregisters when the composable leaves composition.
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

// Accepts either fine or coarse location permission before enabling map location features.
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

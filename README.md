# Map Messages

Android app (Kotlin + Jetpack Compose + Google Maps + Firebase) where users can pin location-based messages, sync them across devices, and read nearby messages.

## Features
- Tap anywhere on the map to select a location.
- Write and save a message to the selected point (or current location).
- Anonymous Firebase Authentication (no sign-up UI required).
- Firestore-backed message sync between all clients.
- Show nearby messages when the user is within `150m` of a pin.
- Live location updates using Fused Location Provider.

## Setup
1. Open `d:\Curs\DailyTasksApp` in Android Studio.
2. Create a Firebase project and Android app for package `com.example.dailytasks`.
3. Download `google-services.json` from Firebase and place it in `app/google-services.json`.
4. In Firebase console, enable:
   - Authentication -> Sign-in method -> Anonymous
   - Firestore Database
5. Add your Google Maps API key to `local.properties`:
   `MAPS_API_KEY=YOUR_API_KEY`
6. Let Gradle sync.
7. Run the `app` configuration on an emulator/device.

## Permissions
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `INTERNET`

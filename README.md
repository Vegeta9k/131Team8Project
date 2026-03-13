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

## Collaborator setup
Collaborators can clone the repository and run the app on their own emulator, but they still need local machine setup and access to the required services.

1. Install Android Studio, the Android SDK, and create an emulator or connect a device.
2. Create a local `local.properties` file with the correct SDK path and Maps key. Example:
```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
MAPS_API_KEY=YOUR_ANDROID_MAPS_KEY
```
3. Add `google-services.json` to `app/google-services.json`.
4. Use Firebase project settings for Android package `com.example.dailytasks`.
5. Enable Firebase Authentication with the `Anonymous` provider.
6. Enable Firestore Database in Native mode.
7. Make sure the Google Maps API key has `Maps SDK for Android` enabled and billing active.
8. If the Maps key is Android-restricted, add each collaborator's debug SHA-1 for package `com.example.dailytasks`.

## Debug SHA-1
Each collaborator usually has a different debug keystore, so each person may need to register their own SHA-1 fingerprint in Google Cloud Console for the Android-restricted Maps key.

PowerShell command:
```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android
```

Look for the `SHA1` line in the output and register it with package `com.example.dailytasks`.

## Notes
- `local.properties` is machine-specific and should not be committed.
- `google-services.json` may be kept out of Git if your team does not want Firebase credentials in the repository.
- If collaborators use the shared Firebase project, they need access to that project.
- If the map is blank on a collaborator machine, the most likely cause is a missing `MAPS_API_KEY` or missing SHA-1 registration for that collaborator.

## Permissions
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `INTERNET`

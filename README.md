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
After cloning the repo, the app still needs two local-only files before it will work fully:
- `local.properties`
- `app/google-services.json`

Without these files:
- The project cannot resolve the local Android SDK path.
- Google Maps may not load.
- Firebase auth and Firestore message sync will not work.

## Collaborator Setup
Collaborators can clone the repository and run the app on their own emulator or device, but they must complete the local setup below first.

1. Install Android Studio and the Android SDK, then create an emulator or connect a device.
2. Open `d:\Curs\DailyTasksApp` in Android Studio.
3. Create or update `local.properties` with the correct SDK path and Maps key. Example:
```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
MAPS_API_KEY=YOUR_ANDROID_MAPS_KEY
```
4. Add `google-services.json` to `app/google-services.json`.
5. In Firebase, use an Android app with package name `com.example.dailytasks`.
6. Enable Firebase Authentication with the `Anonymous` provider.
7. If you want the Register and Log in buttons to work, also enable the `Email/Password` provider in Firebase Authentication.
8. Enable Firestore Database in Native mode.
9. Make sure the Google Maps API key has `Maps SDK for Android` enabled and billing active.
10. If the Maps key is Android-restricted, add each collaborator's debug SHA-1 for package `com.example.dailytasks`.
11. Let Gradle sync, then run the `app` configuration.

## Quick Troubleshooting
- `Firebase is not configured. Add app/google-services.json.`:
  `app/google-services.json` is missing, in the wrong folder, or from the wrong Firebase project.
- Registration says email/password sign-in is not enabled:
  In Firebase Console, go to `Authentication` > `Sign-in method` and enable `Email/Password`.
- Map is blank:
  `MAPS_API_KEY` is missing, invalid, or the debug SHA-1 is not registered for that collaborator.
- Gradle cannot find the SDK:
  `local.properties` is missing or `sdk.dir` points to the wrong location.

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

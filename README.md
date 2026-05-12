# Map Messages

Android app built with Kotlin, Jetpack Compose, Google Maps, and Firebase. Users can browse a live map of pinned messages, unlock nearby notes by physically moving close to them, and create or manage their own posts when signed in.

## What The App Does

The app is a location-based message board:

- Messages are pinned to map coordinates and synced through Firestore.
- Users can only read and vote on messages when they are within `150m` of the pin.
- Signed-in members can write messages, vote, and manage their own posts.
- Guest users can browse the map and open nearby messages, but cannot write, vote, or delete.
- Admin accounts get extra moderation-style controls for nearby messages.

## Current Features

- Google Maps-based main screen with live location updates.
- Anonymous Firebase sign-in for guest access.
- Email/password registration and login for member accounts.
- Password reset flow through Firebase Authentication.
- Firestore-backed real-time message sync across devices.
- Tap a map point to place a new message.
- Fallback to current location when saving a message if no point was manually selected.
- Distance-gated reading and voting within `150m`.
- Upvote/downvote system with persisted vote tracking.
- Automatic deletion of heavily downvoted messages.
- Read/unread marker states and highlighted selected markers.
- "My Messages" screen with local sorting options.
- Account screen showing username, email, user id, permissions, and message count.
- Settings screen with:
  - dark theme toggle
  - profanity filter toggle
  - profanity warning acknowledgement before disabling the filter
- Local profanity filtering for message display.
- Upvote notifications for the signed-in user's messages.

## Roles And Permissions

### Guest

- Can enter the app with anonymous auth.
- Can browse the map.
- Can read nearby messages.
- Cannot write messages.
- Cannot vote.
- Cannot delete messages.

### Registered Member

- Can sign in with email/password.
- Can write messages within `150m` of their current location.
- Can read nearby messages.
- Can vote on nearby messages.
- Can delete their own messages.

### Admin

- Is determined by the email allowlist in [app/src/main/res/values/arrays.xml](/d:/Curs/DailyTasksApp/app/src/main/res/values/arrays.xml:1).
- Can do everything a registered member can do.
- Can delete any nearby message from the map.
- Uses the repository's unlimited-vote/admin path.

## Project Structure

- [MainActivity.kt](/d:/Curs/DailyTasksApp/app/src/main/java/com/example/dailytasks/MainActivity.kt:1)
  Main Compose UI, auth screens, map screen, account screen, settings, and message cards.
- [MainViewModel.kt](/d:/Curs/DailyTasksApp/app/src/main/java/com/example/dailytasks/MainViewModel.kt:1)
  App state, permission rules, distance checks, settings persistence, and Firestore listener lifecycle.
- [MessageSyncRepository.kt](/d:/Curs/DailyTasksApp/app/src/main/java/com/example/dailytasks/MessageSyncRepository.kt:1)
  Firebase Auth + Firestore reads/writes, username checks, delete rules, and voting transaction logic.
- [UpvoteNotifier.kt](/d:/Curs/DailyTasksApp/app/src/main/java/com/example/dailytasks/UpvoteNotifier.kt:1)
  Notification channel creation and upvote alerts.
- [LocationMessage.kt](/d:/Curs/DailyTasksApp/app/src/main/java/com/example/dailytasks/LocationMessage.kt:1)
  Message model used across the app.

## Setup

After cloning the repo, the app still needs two local-only files before it will work fully:

- `local.properties`
- `app/google-services.json`

Without these files:

- Android Studio may not find the local SDK.
- Google Maps will not load correctly.
- Firebase Authentication will not initialize.
- Firestore sync, voting, and account-backed features will not work.

## Local Setup

1. Install Android Studio and the Android SDK.
2. Create an emulator or connect a physical Android device.
3. Open `d:\Curs\DailyTasksApp` in Android Studio.
4. Create or update `local.properties` with your SDK path and Maps key:

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
MAPS_API_KEY=YOUR_ANDROID_MAPS_KEY
```

5. Add your Firebase config file at `app/google-services.json`.
6. In Firebase Console, create or use an Android app with package name `com.example.dailytasks`.
7. Enable `Authentication`:
   - `Anonymous`
   - `Email/Password`
8. Enable Firestore Database in Native mode.
9. Make sure the Google Maps key has `Maps SDK for Android` enabled and billing active.
10. If the Maps key is Android-restricted, register the debug SHA-1 for package `com.example.dailytasks`.
11. Sync Gradle and run the `app` configuration.

## Firebase Notes

- Message documents are stored in the `messages` collection.
- User profile documents are stored in the `users` collection.
- Per-user vote tracking is stored in each message's `votes` subcollection.
- Registered usernames are checked case-insensitively using a lowercase field.

## Quick Troubleshooting

- `Firebase is not configured. Add app/google-services.json.`  
  `app/google-services.json` is missing, in the wrong folder, or from the wrong Firebase project.

- Registration/login says email/password sign-in is not enabled  
  In Firebase Console, go to `Authentication` > `Sign-in method` and enable `Email/Password`.

- Guest login works but registered features do not  
  Make sure both `Anonymous` and `Email/Password` providers are enabled.

- Map is blank  
  `MAPS_API_KEY` is missing, invalid, restricted incorrectly, or the debug SHA-1 is not registered.

- Gradle cannot find the SDK  
  `local.properties` is missing or `sdk.dir` points to the wrong location.

- Messages do not unlock nearby  
  Check that location permission was granted and the device/emulator is providing a usable location.

- Notifications do not appear  
  On Android 13+, confirm the notification permission was granted and app notifications are enabled.

## Debug SHA-1

Each collaborator usually has a different debug keystore, so each person may need to register their own SHA-1 fingerprint in Google Cloud Console for the Android-restricted Maps key.

PowerShell command:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android
```

Look for the `SHA1` line in the output and register it with package `com.example.dailytasks`.

## Permissions Used

- `INTERNET`
- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`
- `POST_NOTIFICATIONS`

## Notes

- `local.properties` is machine-specific and should not be committed.
- `google-services.json` may be kept out of Git if your team does not want Firebase credentials in the repository.
- The app currently relies on real-time Firestore listeners for message updates.
- Admin access is currently controlled by a local email allowlist in [arrays.xml](/d:/Curs/DailyTasksApp/app/src/main/res/values/arrays.xml:1).

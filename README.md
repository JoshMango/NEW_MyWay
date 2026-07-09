<img src="./app/assets/templogo.png" alt="logo" width="96">

# MyWay 🗺️

**The ultimate group travel companion.** Real-time location sharing, group chats, waypoint planning, and invite management — all in one beautifully crafted Android app. Stop juggling Google Maps, Life360, and Messenger. MyWay does it all.

## 🛠 Language and Framework
![Tech Stack](https://skills-icons.vercel.app/api/icons?i=kotlin,firebase,jetpackcompose,gradle)

100% Kotlin + Jetpack Compose (Material3), Google Maps via `maps-compose`, Firebase Auth. No Java, no XML layouts.

## 🚀 Getting Started

### Prerequisites
- Android Studio
- Android SDK 24+ (API level 24 is minimum; targeting API 36)
- Firebase account with a project initialized for Android

### Setup

1. **Clone and open in Android Studio**
   ```bash
   git clone https://github.com/JoshMango/NEW_MyWay
   cd NEW_MyWay
   ```

2. **Configure Firebase**
   - Create a Firebase project at [firebase.google.com](https://firebase.google.com).
   - Download `google-services.json` for your Android app and place it at `app/google-services.json`.
   - Enable **Authentication** (Email/Password, Google, GitHub) and **Firestore Database** (production mode) in the Firebase console.

3. **Add Google Maps and Place API key**
   - Create a `secrets.properties` file at the project root (it's gitignored):
     ```properties
     MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
     ```
   - Get your key from [Google Cloud Console](https://console.cloud.google.com) and restrict it to your app's package name + debug SHA-1.

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug  # Requires connected device or emulator
   ```

## 📖 Project Structure

Everything is Kotlin + Jetpack Compose in one flat package — no Java, no XML layouts. Local data lives in `App.kt` (SharedPreferences); everything social lives in Firebase Firestore.

```
app/src/main/
├── java/com/usc/myway/
│   ├── LoginActivity.kt / RegisterActivity.kt   # Auth screens (Firebase)
│   ├── OnboardingActivity.kt                    # First-run @tag claim
│   ├── AuthComponents.kt                        # Shared auth composables
│   ├── MainActivity.kt                          # Map home / hub (maps-compose)
│   ├── MapMarkers.kt                            # Personal pin/label rendering (MapMarkerManager)
│   ├── PlaceSheets.kt                           # Marker + landmark-detail bottom sheets
│   ├── Directions.kt / DirectionsUi.kt          # Routes API client + directions/nav UI
│   ├── Sidebar.kt / BottomCard.kt / SearchBar.kt # Map overlays (drawer, stats, voice search)
│   ├── ProfileActivity.kt / ProfileCard.kt      # Profile settings + reusable Discord-style card
│   ├── Avatar.kt                                # AvatarCircle, base64 image encode/decode
│   ├── FriendsActivity.kt / Friends.kt          # Find friends by @tag, requests, friendships
│   ├── GroupsActivity.kt / Groups.kt            # Group list + Firestore group/chat helpers
│   ├── GroupChatActivity.kt                     # Group chat, images, info sheet (roles/roster)
│   ├── Trip.kt / TripArrows.kt                  # Group Trips (live location, session pins, offers)
│   ├── TripLocationService.kt                   # Foreground location publisher (heartbeat/TTL)
│   ├── LiveShare.kt / LiveLocationActivity.kt   # Messenger-style live-location sharing
│   ├── SharedPinActivity.kt                     # Shared-pin preview screen
│   ├── CollectionsActivity.kt / Collection.kt   # Collections viewer + model
│   ├── SettingsActivity.kt                      # Pin colour/icons, delete local data
│   ├── Notifier.kt / NotificationHub.kt         # In-app heads-up notifications
│   ├── FcmTokens.kt / MyFirebaseMessagingService.kt # FCM push (killed-app notifications)
│   ├── Profiles.kt                              # User profile + @tag/banner Firestore helpers
│   ├── App.kt                                   # Application singleton, local data store, settings
│   └── ui/theme/Theme.kt                        # Material3 theme (MyWayTheme)
├── res/
│   ├── raw/map_dark.json                        # Google Maps night style
│   ├── drawable/, drawable-nodpi/               # Icons (Google, GitHub, launcher)
│   └── values/                                  # colors.xml, theme.xml, strings.xml
firestore.rules                                  # Firestore security rules (deploy via Firebase CLI)
firebase.json                                    # Firebase CLI config (rules + functions)
functions/                                       # Cloud Functions — FCM push on new message / trip start
```

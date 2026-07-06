# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

MyWay is a native Android app being built into a group-travel companion (real-time location sharing, group chats, shared waypoints, invites). Package: `com.usc.myway`. minSdk 24, target/compileSdk 36. AGP 9.2.1 with **built-in Kotlin** (no `kotlin-android` plugin), Kotlin 2.2.10, Jetpack Compose (BOM 2026.06.00, Material3), `maps-compose` 6.4.1.

The app is **fully Kotlin + Jetpack Compose** — there are no Java files and no XML layouts. Data is local-only (`SharedPreferences` via `App.kt`); Firebase Auth is live, Firestore is a declared dependency but not yet used. Long-term the team wants a Kotlin-shared core for an eventual iOS port — but `maps-compose`, the Places SDK, FusedLocation, and Firebase are all Android-only, so that will need `expect/actual` (or a native layer) behind them.

## Build prerequisites

The project will not build without two gitignored files (obtain/create them locally):

- `app/google-services.json` — Firebase config. Download from the Firebase console (Android app `com.usc.myway`).
- `secrets.properties` (repo root) — holds `MAPS_API_KEY=<your key>`. Injected into `BuildConfig.MAPS_API_KEY` and the manifest `${MAPS_API_KEY}` placeholder by the secrets-gradle-plugin (configured via the `secrets { }` block in `app/build.gradle.kts`). `local.defaults.properties` is a committed placeholder so the project configures without the real key (Maps just won't render).

For Google Sign-In on device you also need the debug SHA-1 registered in Firebase (`gradlew.bat signingReport`).

## Commands

Build/test via the Gradle wrapper (`gradlew.bat` on Windows):

- Build debug APK: `gradlew.bat assembleDebug`
- Install to connected device/emulator: `gradlew.bat installDebug`
- Run JVM unit tests: `gradlew.bat test`
- Run instrumented tests (device/emulator required): `gradlew.bat connectedAndroidTest`
- Lint: `gradlew.bat lint`

No CI config; only placeholder template tests exist (`app/src/test`, `app/src/androidTest`) — no real test suite yet.

## Architecture

All code is in one flat package: `app/src/main/java/com/usc/myway/`. No `models/`/`viewmodels/`/`data/` layering, no repository pattern, no DI. Every screen is a `ComponentActivity` + `setContent { … }`, linked by plain `Intent` navigation (no Navigation Component). Reuse `MyWayTheme` (`ui/theme/Theme.kt`, Material3 teal, light/dark) everywhere.

### Data layer — `App.kt` is the local database
The `Application` singleton (`android:name=".App"`) holds in-memory `MutableList<Location>`, note/name/placeId `MutableMap<String,String>`s, and `MutableList<Collection>`, all persisted to `SharedPreferences`. There is **no LiveData/Flow/observer**: Compose screens read `App`'s live lists (`app.myLocations`, `app.locationNotes`, `app.collections`) and re-read them off a `refreshKey` Int state that's bumped after every mutation. Cross-screen live values (GPS stats, drawer switch/theme positions) live in plain state-holder classes (`StatsState`, `SidebarState`) whose properties are `mutableStateOf`; the activity writes to them.

**No dedicated Waypoint class.** A waypoint is a stock `android.location.Location` joined to its name/note/collection membership by a derived string key: `App.locationKey(lat, lng)` → `"%.6f,%.6f"`. That key is the de-facto waypoint ID everywhere (`locationNotes`, `Collection.locationKeys`, prefs entry names). Add new persisted waypoint attributes as key-based side tables in `App.kt`, not as model fields. Landmark POIs get a **placeId side table** (`App.isLandmark(key)`) so a saved landmark keeps Google's native map icon instead of getting a red pin.

### Screens
- **Auth** — `LoginActivity.kt` (launcher), `RegisterActivity.kt`; `AuthComponents.kt` (`AuthTextField`, `SocialButton`). UI state is `mutableStateOf` on the activity; async Firebase callbacks flip it. Firebase Auth: email/password with **required email verification** (register sends the link, sign-in gated on `isEmailVerified`), Google Sign-In (classic `GoogleSignIn` API — deprecated but simplest), GitHub via Firebase-hosted OAuth (`OAuthProvider`). Social logins skip verification. **Account linking**: on `FirebaseAuthUserCollisionException` it re-authenticates with the original provider then `linkWithCredential()` (handles the empty-`fetchSignInMethodsForEmail` enumeration-protection case with a provider chooser). A couple of edge dialogs still use `MaterialAlertDialogBuilder`.
- **Map home** — `MainActivity.kt`. `maps-compose` `GoogleMap`; the imperative marker/label/POI logic runs on the raw `GoogleMap` obtained via `MapEffect`. Compose overlays: gradient header, hamburger drawer (`Sidebar.kt`), search (`SearchBar.kt`), bottom stats card (`BottomCard.kt`). Location tracking uses `FusedLocationProviderClient`; permission + the two map-picker launches use `registerForActivityResult`.
- **Waypoint/address picker** — `MapPickerActivity.kt`. `maps-compose` map with a draggable marker + Places autocomplete; returns `picked_lat`/`picked_lng`/`picked_address`/`picked_name`/`picked_notes` via the ActivityResult contract. `mode` extra is `"waypoint"` or `"address"`.
- **Saved locations** — `ShowSavedLocations.kt`. Compose `TabRow` (All Locations / Collections) with search, empty/no-results states, waypoint & collection cards, and all edit/collection/delete dialogs.

### Map markers & interaction (`MapMarkers.kt` + `PlaceSheets.kt`)
- `MapMarkerManager` renders markers on the raw `GoogleMap`: red pins for normal waypoints, **none** for landmarks (Google's native POI icon stays), plus note-label cards drawn as `Canvas` bitmaps. Note labels are **billboard** markers (they stay upright as the map rotates, like Google's labels) floating slightly north of the point, and they **collapse to a small pencil marker below zoom 18** (`applyNoteZoom`) so the map stays clean when zoomed out. Label/pencil bitmaps are **dark-mode aware** (the `dark` flag is threaded through `refresh`).
- Tap a red pin or a note label → Compose `ModalBottomSheet` `MarkerActionsSheet` (title, geocoded address, Add/Edit Note, Add to Collection, Delete). Tap a Google POI icon → `PlaceDetailsSheet` (lazy photo gallery, rating, open/closed badge, hours, phone, website, reviews, plus the user's saved note). Adding a note/collection to a POI **auto-saves it as a waypoint**; Delete only appears once it's saved.
- Which sheet/dialog is open is `mutableStateOf` state on `MainActivity` (`activeSheet` sealed type; `noteKey`/`collectionKey`/`deleteKey`/`savePinLatLng`). Mutations call `refresh()` → bumps `refreshKey` → the `MapEffect(refreshKey, dark)` re-renders markers. The pin-on-map path adds a temporary green marker then opens the save-pin dialog.
- **Focus**: taps in the saved-locations list return to `MainActivity` with `focus_lat`/`focus_lng`; `handleFocusIntent` (called from `onResume`/`onNewIntent`) animates the camera there.

### Cross-cutting
- **Dark mode** is app-controlled: `App.isDarkMode()/setDarkMode()` + `AppCompatDelegate.setDefaultNightMode()`. The drawer theme toggle flips night mode and `recreate()`s the activity. Map night styling is `MapProperties(mapStyleOptions = res/raw/map_dark.json)`; the marker note bitmaps switch palette off the same dark flag.
- **Places cost control** (do not regress): autocomplete uses an `AutocompleteSessionToken` + 300ms debounce so a search and its final `fetchPlace` bill as one session; `fetchPlace`/`isOpen`/`fetchPhoto` results are cached (`placeCache`/`isOpenCache`/`photoCache`) and photos load lazily via `LazyRow`. Reverse-geocoding uses the free Android `Geocoder` on a background executor, deduped within ~15m.
- **App/launcher icon** is `res/drawable-nodpi/ic_launcher_logo.png`, referenced directly in the manifest (not an adaptive icon).

## Future direction

1. **Local `SharedPreferences` → Firebase (Firestore).** Move pins, notes, names, placeIds, and collections out of `App.kt`'s prefs storage into Firestore, scoped per user/group (users, groups, group locations/messages/waypoints). The `firebase-firestore` dependency is already present. Introduce a real data/repository layer here (the point to break the "`App.kt` is the database" pattern) and drive UI from snapshots/Flow instead of manual `refresh()` calls.
2. **iOS via Kotlin Multiplatform.** UI is Compose and the data layer (`App.kt`/`Collection.kt`) is Kotlin, but `maps-compose`, the Places SDK, FusedLocation, and Firebase are Android-only — an iOS port needs `expect/actual` abstractions (or a native map/location layer) behind those before any code is shared.

When adding features that touch data, prefer designing them so the Firestore migration is easier (e.g., don't add more prefs-key side tables than necessary).

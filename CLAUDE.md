# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

MyWay is a native Android app being built into a group-travel companion (real-time location sharing, group chats, shared waypoints, invites). Package: `com.usc.myway`. minSdk 24, target/compileSdk 36. AGP 9.0.1 with **built-in Kotlin** (no `kotlin-android` plugin), Kotlin 2.2.10, Jetpack Compose (BOM 2026.06.00, Material3).

The codebase is **mid-migration**: the auth layer is Kotlin + Jetpack Compose; everything else (map, waypoints, collections) is still Java + XML views. Data is currently local-only (`SharedPreferences` via `App.java`); Firebase Auth is live, Firestore is a declared dependency but not yet used.

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
- Run a single unit test: `gradlew.bat test --tests "com.usc.myway.ExampleUnitTest.addition_isCorrect"`
- Run instrumented tests (device/emulator required): `gradlew.bat connectedAndroidTest`
- Lint: `gradlew.bat lint`

No CI config; only placeholder template tests exist (`app/src/test`, `app/src/androidTest`) — no real test suite yet.

## Architecture

All code is in one flat package: `app/src/main/java/com/usc/myway/`. No `models/`/`viewmodels/`/`data/` layering, no repository pattern, no DI. Two coexisting UI styles (Compose auth, XML/Java everything-else) linked by `Intent` navigation.

### Data layer — `App.java` is the local database
The `Application` singleton (`android:name=".App"`) holds in-memory `List<Location>`, note/name `Map<String,String>`s, and `List<Collection>`, all persisted to `SharedPreferences`. Screens call `App` getters, mutate the returned live list, then manually refresh (`refreshMapMarkers()`, `notifyDataSetChanged()`, re-fetch in `onResume()`). No LiveData/Flow/observer.

**No dedicated Waypoint class.** A waypoint is a stock `android.location.Location` joined to its name/note/collection membership by a derived string key: `App.locationKey(lat, lng)` → `"%.6f,%.6f"`. That key is the de-facto waypoint ID everywhere (`locationNotes`, `Collection.locationKeys`, prefs entry names). Add new persisted waypoint attributes as key-based side tables in `App.java`, not as model fields.

### Auth layer — Kotlin + Compose (the migrated slice)
- `LoginActivity.kt` (launcher), `RegisterActivity.kt` — `ComponentActivity` + `setContent`. UI state is `mutableStateOf` held on the activity; async Firebase callbacks flip it. Rare edge dialogs (verify/link/password) reuse `MaterialAlertDialogBuilder`, not Compose.
- `AuthComponents.kt` — shared `AuthTextField` and `SocialButton` composables.
- `ui/theme/Theme.kt` — `MyWayTheme` (Material3, teal scheme, light/dark).
- Firebase Auth: email/password with **required email verification** (register sends the link, sign-in is gated on `isEmailVerified`), Google Sign-In (classic `GoogleSignIn` API — deprecated but simplest; see `ponytail:` note), and GitHub via Firebase-hosted OAuth (`OAuthProvider`, no extra SDK). Social logins skip verification.
- **Account linking**: on `FirebaseAuthUserCollisionException`, the app fetches the existing provider(s), has the user re-authenticate with their original method, then `linkWithCredential()`. Handles the email-enumeration-protection case (empty `fetchSignInMethodsForEmail`) by offering a provider chooser.

### Navigation & map interaction (XML/Java)
Plain `Intent` navigation, no Navigation Component. `LoginActivity` → `MainActivity` (map + sidebar; **logout** lives in the sidebar, clears Firebase + Google session). `MainActivity` → `MapPickerActivity` (`startActivityForResult`, codes `MAP_PICKER_REQUEST`/`WAYPOINT_PICKER_REQUEST`) and → `ShowSavedLocations` (`ViewPager2` + `TabLayout` hosting `AllWaypointsFragment` and `CollectionsListFragment`). List taps return to `MainActivity` with `focus_lat`/`focus_lng` to recenter (`handleFocusIntent()`).

**Marker interaction (important gotcha):** a Google Maps info window is a single static bitmap — you *cannot* put individually clickable buttons in it. So the flow is two-step: tap a pin → `custom_infowindow.xml` popup (title, note, an "Edit" affordance); tap the popup (`setOnInfoWindowClickListener`) → `BottomSheetDialog` (`sheet_marker_actions.xml`) with the real **Add/Edit Note** and **Delete Location** buttons. Persistent note labels are separate flat markers tracked in `labelMarkers` (guarded against in every marker listener).

**Two add-pin paths** (both must save name AND note): `MapPickerActivity` waypoint mode returns `picked_name`/`picked_notes` → handled in `MainActivity.onActivityResult`; and the pin-on-map flow → `showSavePickedLocationDialog` (`dialog_savepin.xml`, has `et_pin_name` + `et_pin_notes`).

### Cross-cutting
- **Adapters own business logic** — `WaypointAdapter`/`CollectionAdapter`/`CollectionItemAdapter` open edit/delete/add-to-collection dialogs and mutate `App` directly, then self-refresh.
- **Dark mode** is app-controlled: `App.isDarkMode()/setDarkMode()` + `AppCompatDelegate.setDefaultNightMode()`. Map night styling is separate (`res/raw/map_dark.json` via `setMapStyle`); its `isDarkMode()` helper is duplicated in `MainActivity` and `MapPickerActivity`.
- **Location permission** (`ACCESS_FINE_LOCATION`) uses the old `requestPermissions()`/`onRequestPermissionsResult()` API, requested only in `MainActivity`; `MapPickerActivity` checks but doesn't request.
- **App/launcher icon** is `res/drawable-nodpi/ic_launcher_logo.png` (from `app/assets/templogo.png`), referenced directly in the manifest — not an adaptive icon, so it isn't mask-cropped. Old `mipmap/ic_launcher*` resources are unused.

## Future direction

Two planned migrations — keep new work aligned with these rather than entrenching the current shortcuts:

1. **XML/Java → Jetpack Compose (incremental).** Auth is done. Next candidates are self-contained screens (`ShowSavedLocations` + its fragments/adapters, then dialogs), leaving the Google Maps screen (`MainActivity`) for last (needs `maps-compose`). Reuse `MyWayTheme` and the `AuthComponents` patterns. The disabled post-login greeting logic was removed — re-add it as a Compose banner in `MainActivity` when it migrates.
2. **Local `SharedPreferences` → Firebase (Firestore).** Move pins, notes, names, and collections out of `App.java`'s prefs storage into Firestore, scoped per user/group (users, groups, group locations/messages/waypoints). The `firebase-firestore` dependency is already present. Introduce a real data/repository layer here (this is the point to break the "`App.java` is the database" pattern) and drive UI from snapshots/Flow instead of manual `refresh*()` calls.

When adding features that touch data, prefer designing them so the Firestore migration is easier (e.g., don't add more prefs-key side tables than necessary).

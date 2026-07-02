# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

MyWay is a native Android app (Java, Gradle Kotlin DSL) for saving map waypoints/locations into collections. Package: `com.usc.myway`. minSdk 24, targetSdk/compileSdk 36. Uses Google Maps SDK, Google Places SDK (Autocomplete), and Play Services Fused Location — no backend, no database (SQLite/Room); all persistence is local via `SharedPreferences`.

## Commands

Build/test via the Gradle wrapper (`gradlew.bat` on Windows):

- Build debug APK: `gradlew.bat assembleDebug`
- Install to connected device/emulator: `gradlew.bat installDebug`
- Run JVM unit tests: `gradlew.bat test`
- Run a single unit test: `gradlew.bat test --tests "com.usc.myway.ExampleUnitTest.addition_isCorrect"`
- Run instrumented tests (device/emulator required): `gradlew.bat connectedAndroidTest`
- Lint: `gradlew.bat lint`

There is no CI config and only placeholder template tests exist (`app/src/test`, `app/src/androidTest`) — no real test suite yet.

## Architecture

All app code lives in one flat package: `app/src/main/java/com/usc/myway/`. There are no `models/`/`viewmodels/`/`data/` sub-packages, no MVVM/repository layering, and no dependency injection. The shape to understand:

- **`App.java` is the local database.** It's the `Application` singleton (`android:name=".App"`), holding in-memory `List<Location>`, `Map<String,String>` (notes/names), and `List<Collection>` fields that are read/written directly by Activities/Fragments/Adapters via `(App) context.getApplicationContext()`. All CRUD and `SharedPreferences` read/write goes through it. There is no observer pattern (no LiveData/Flow) — screens call `App` getters, mutate the returned live list, then manually trigger `notifyDataSetChanged()` / re-fetch on `onResume()`.
- **No dedicated Waypoint class.** A waypoint is a stock `android.location.Location` (lat/lng) joined to its name/note/collection-membership via a derived string key: `App.locationKey(lat, lng)` → `"%.6f,%.6f"`. This key is the de-facto waypoint ID used everywhere (`locationNotes` map, `Collection.locationKeys`, `SharedPreferences` entry names). When adding a new persisted attribute for a waypoint, follow this key-based side-table pattern in `App.java` rather than introducing a model class.
- **Navigation is plain `Intent`-based**, no Navigation Component. `MainActivity` (launcher) → `MapPickerActivity` (pick/create a waypoint or address, via `startActivityForResult` with request codes `MAP_PICKER_REQUEST`/`WAYPOINT_PICKER_REQUEST`) and → `ShowSavedLocations` (a `ViewPager2` + `TabLayout` hosting `AllWaypointsFragment` and `CollectionsListFragment`). List taps navigate back to `MainActivity` with `focus_lat`/`focus_lng` extras to re-center the map (`handleFocusIntent()` in `onResume()`).
- **`MapsActivity.java` is dead code** — a fully-built alternate map screen still declared in the manifest, but nothing constructs an `Intent` to it. Don't extend it assuming it's reachable; check before removing/reusing it.
- **Adapters own business logic, not just binding.** `WaypointAdapter`, `CollectionAdapter`, `CollectionItemAdapter` open edit/delete/add-to-collection dialogs and call `App` mutation methods directly, then refresh themselves.
- **External services are Google's only** — Maps SDK, Places Autocomplete, Fused Location, and on-device `Geocoder` for reverse geocoding. No app backend/API layer.
- **Dark mode** is app-controlled, not just system: `App.isDarkMode()/setDarkMode()` persists a flag and calls `AppCompatDelegate.setDefaultNightMode()` both at startup and from an in-app toggle. Map dark styling is separate (`res/raw/map_dark.json` applied via `GoogleMap.setMapStyle`) and its dark-mode check is duplicated between `MainActivity` and `MapPickerActivity` rather than shared.
- **Location permission** (`ACCESS_FINE_LOCATION`) uses the old manual `requestPermissions()`/`onRequestPermissionsResult()` API, requested only from `MainActivity`; `MapPickerActivity`/`MapsActivity` check but don't request it themselves, relying on `MainActivity` having asked first.

## Known issue: exposed API key

The Google Maps/Places API key is hardcoded in plaintext in both `app/src/main/AndroidManifest.xml` (`com.google.android.geo.API_KEY`) and `res/values/strings.xml` (`google_maps_key`), and is committed to git history. The secrets-gradle-plugin is applied in `app/build.gradle.kts` but not actually wired up (there's a stale TODO in the manifest describing the intended `local.properties` → `${MAPS_API_KEY}` setup). Do not add new secrets the same way — if touching this, move the key to `local.properties` (gitignored) and flag that the committed key should be rotated.

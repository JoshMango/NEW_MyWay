# MyWay 🗺️

**The ultimate group travel companion.** Real-time location sharing, group chats, waypoint planning, and invite management — all in one beautifully crafted Android app. Stop juggling Google Maps, Life360, and Messenger. MyWay does it all.

## 🛠 Language and Framework
![Tech Stack](https://skills-icons.vercel.app/api/icons?i=kotlin,java,firebase,jetpackcompose,gradle)

## 🚀 Getting Started

### Prerequisites
- Android Studio (latest)
- Android SDK 24+ (API level 24 is minimum; targeting API 36)
- Firebase account with a project initialized for Android

### Setup

1. **Clone and open in Android Studio**
   ```bash
   git clone <repo-url>
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

```
app/src/main/
├── java/com/usc/myway/
│   ├── LoginActivity.kt / RegisterActivity.kt     # Auth screens (Compose)
│   ├── MainActivity.java                          # Map and main navigation (XML/Java)
│   ├── MapPickerActivity.java                     # Waypoint picker (XML/Java)
│   ├── ShowSavedLocations.java                    # Collections and waypoint list (XML/Java)
│   ├── App.java                                   # App singleton, local data store
│   └── ui/theme/Theme.kt                          # Material3 theme for Compose
├── res/
│   ├── layout/                                    # XML layouts (map, collections, dialogs)
│   ├── drawable/                                  # Icons (Google, GitHub)
│   └── values/colors.xml, theme.xml               # Theming
```

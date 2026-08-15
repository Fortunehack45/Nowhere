# Nowhere — Precision GPS & Route Simulator

**Nowhere** is an ultra-premium, professional native Android mock-location engine, route simulator, and destination management suite with a luxury **Red & White** aesthetic.

---

## 🌟 Full Feature Suite

### 1. 🔍 Real-Time Search & Search History
- **Live Suggestions**: Real-time debounced Geocoder search suggestions as you type.
- **Local Search History (Room Database)**: Automatically stores recent searches with address titles, snippets, coordinates, and timestamps.
- **Instant History Dropdown**: Tapping the search bar when empty instantly shows recent searches with individual delete and fast 1-tap teleportation.

### 2. 🛣️ Advanced Route Management Suite
- **Interactive Waypoint Plotting**: Tap anywhere on OpenStreetMap to plot route nodes with custom Crimson polyline drawing.
- **Live Route Metrics**: Real-time calculation of total path distance (formatted in km or miles) and waypoint count.
- **Route Reverse (`Reverse`)**: 1-Tap reverses the entire waypoint list so you can retrace your steps.
- **Save & Load Routes (`Saved Routes`)**: Save custom route circuits to a local Room database with custom names, speed, and looping settings.
- **GPX Import Engine**: Import standard trackpoint XML `.gpx` files.
- **Realistic Kinematics**: Realistic acceleration from standstill (`2 m/s²`), centripetal deceleration on sharp turns (>35°), and dynamic compass heading calculations.

### 3. 📌 Pins, Bookmarks & Favorites
- **Save Pinned Locations**: 1-Tap modal to bookmark coordinates with custom names and category tags (Work, Travel, Field Test, Home).
- **Saved Destinations Drawer**: Slide-up sheet with search filtering, tag chips, and instant teleportation.
- **JSON Backup Engine**: One-click export (clipboard & system share sheet) and file import.

### 4. 🕹️ Military-Grade Radar Joystick (`JoystickView`)
- **HUD Steering View**: Concentric radar distance rings, cardinal markings (`N`, `E`, `S`, `W`), and dynamic vector trail.
- **Continuous 360° Velocity**: Smoothly steer anywhere on the map with configurable max speed slider (2–60 km/h).

### 5. ⚙️ Advanced Engine & Settings (`SettingsActivity`)
- **Google Play Fused Provider**: Injects `"fused"` test provider for complete compatibility with Google Play Services `FusedLocationProviderClient`.
- **GPS Jitter Randomizer**: Configurable antenna drift with custom radius slider (0.5m – 10.0m).
- **Coordinate Truncation**: Select Full, 6 Decimals (~0.1m), or 4 Decimals (~11m) precision.
- **Reported Accuracy & Altitude**: Configurable accuracy metadata (0.5m – 20.0m) and baseline elevation.
- **Map Tile Sources**: Street (Mapnik), Terrain (OpenTopoMap), Vector (Wikimedia), and Satellite (USGS Sat).
- **App Themes & Distance Units**: Dark Obsidian / Light / System Default; Metric (KM/H, m) / Imperial (MPH, ft).
- **Haptic Feedback**: Vibration pulse on teleport engagement.

---

## 📱 App Pages & Screens

1. **Welcoming & Onboarding Page** ([`WelcomeActivity.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/WelcomeActivity.kt)): Entry splash, feature highlights, and live Developer Options readiness checklist.
2. **Main Simulation HUD Dashboard** ([`MainActivity.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/MainActivity.kt)): Primary map, unified search & history dropdown, route suite, radar joystick overlay, and live telemetry.
3. **Advanced Engine & Settings Page** ([`SettingsActivity.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/SettingsActivity.kt)): Full control over mock providers, jitter, truncation, tile sources, themes, and units.
4. **Saved Routes Drawer** ([`SavedRoutesBottomSheet.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/routes/SavedRoutesBottomSheet.kt)): Manage, load, and delete saved route circuits.
5. **Saved Destinations Drawer** ([`FavoritesBottomSheet.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/favorites/FavoritesBottomSheet.kt)): Slide-up bookmarks manager with category tags and JSON backup.
6. **Developer Setup Modal** ([`SetupGuideDialog.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/dialogs/SetupGuideDialog.kt)): Developer Options shortcut.
7. **Background Resilience Modal** ([`BatteryOptimizationDialog.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/dialogs/BatteryOptimizationDialog.kt)): Background killer guidance.
8. **Save Route Modal** ([`SaveRouteDialog.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/dialogs/SaveRouteDialog.kt)) & **Save Destination Modal** ([`SaveFavoriteDialog.kt`](file:///c:/Users/USER%20PC/Desktop/Nowhere/app/src/main/java/com/fakegps/mocklocation/ui/dialogs/SaveFavoriteDialog.kt)).

---

## 🎨 Design System: Red & White Luxury

- **Brand Primary**: Vibrant Crimson Red (`#E41B1B`), Bright Coral Red (`#FF3B3B`), Deep Ruby (`#B91C1C`).
- **Contrast Accents**: Crisp Pure White (`#FFFFFF`), Soft Slate White (`#F8FAFC`).
- **Surfaces**: Obsidian Glass (`#EB121826`) and Deep Charcoal (`#0A0D14`).
- **Icons**: Material You (Android 13+) themed adaptive icons with single-color monochrome mask.

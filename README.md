# F1 Clock — Android Live Wallpaper

A native Android live wallpaper that replays real F1 races on your home screen using actual telemetry data. New race every hour, perfectly synced with [f1.yumyumhq.com](https://f1.yumyumhq.com/).

## Features

- **50 real races** from the 2019–2024 F1 seasons
- **Actual telemetry data** — cars follow their real racing lines
- **Hourly rotation** — new race every hour, synced with the web version
- **Real-time positions** — leaderboard with live position tracking
- **Pit stops & tire stints** — see compound changes and pit count
- **Fastest lap flash** — purple glow when the FL is set
- **DNF detection** — retired cars shown in leaderboard
- **Sector-colored tracks** — three distinct sector colors
- **Configurable** — FPS, overlays, car size, battery settings

## Setup

1. Open the project in **Android Studio** (Koala or newer)
2. Let Gradle sync (downloads dependencies automatically)
3. Build & run on your device or emulator
4. Long-press home screen → **Wallpapers** → **Live wallpapers** → **F1 Clock**
5. Hit **Set wallpaper** ✨

## Requirements

- Android 8.0+ (API 26)
- Internet connection (fetches race data from f1.yumyumhq.com)
- ~50MB RAM for the wallpaper service

## Architecture

```
com.yumyumhq.f1clock/
├── F1ClockWallpaperService.kt   — Main service + Engine
├── data/
│   ├── RaceData.kt              — JSON data models (20 types)
│   ├── F1ClockApi.kt            — Retrofit API interface
│   └── RaceRepository.kt       — Network + caching layer
├── renderer/
│   ├── TrackRenderer.kt        — Canvas rendering engine
│   └── RaceAnimator.kt         — Time sync logic
└── settings/
    └── WallpaperSettingsActivity.kt  — Preferences UI
```

## How It Works

### Time Synchronization
```kotlin
raceTime = (System.currentTimeMillis() % 3600000) / 1000f
```
Same formula as the web version. Both show the exact same race position at any moment.

### Rendering Pipeline
1. Fetch race JSON from API (cached per hour)
2. Compute coordinate transform to fit track on screen
3. Each frame: interpolate car positions at current race time
4. Draw track outline → start/finish line → cars → labels → leaderboard → race info

### Battery Optimization
- Stops rendering when wallpaper is not visible
- Configurable FPS (15/30/60)
- Data cached per hour — single network request
- No wake locks — uses Handler.postDelayed for scheduling

## Settings

Access via wallpaper picker → gear icon:

| Setting | Options | Default |
|---------|---------|---------|
| Frame Rate | 15 / 30 / 60 FPS | 30 |
| Driver Labels | On/Off | On |
| Leaderboard | On/Off | On |
| Race Info | On/Off | On |
| Tire Colors | On/Off | Off |
| Glow Effects | On/Off | On |
| Sector Colors | On/Off | On |
| Car Dot Size | S/M/L | Medium |
| Battery Saver | On/Off | On |
| Background Darkness | 4 levels | Dark |

## API

Consumes: `GET https://f1.yumyumhq.com/api/race`

Returns JSON with:
- `trackOutline` — array of {x, y} points defining the circuit
- `trackSectors` — sector1/2/3 point arrays
- `drivers` — map of driver number → {code, color, name, team}
- `locations` — map of driver number → [{t, x, y}, ...] telemetry positions
- `positions` — map of driver number → [{t, position}, ...] race positions
- `events`, `laps`, `stints`, `pitStops`, `fastestLap`

## Future Enhancements

- [ ] Chequered flag animation at race end
- [ ] Podium celebration overlay
- [ ] Event banner system (safety car, flags)
- [ ] Offline mode with bundled race data
- [ ] AMOLED-optimized color scheme
- [ ] Widget companion with race info
- [ ] Notification when race changes

## Tech Stack

- **Kotlin** + Coroutines
- **Retrofit 2** + Gson for networking
- **Android Canvas API** for hardware-accelerated rendering
- **AndroidX Preferences** for settings
- **WallpaperService** framework

## License

Personal project by Chris Gillis. Not for distribution.

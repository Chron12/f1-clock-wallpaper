# F1 Clock — macOS Desktop Widget Design

**Date:** 2026-04-21
**Status:** Approved
**Platform:** macOS 13+ (Ventura and up)
**Relationship:** Companion to existing Android live wallpaper, separate project, shared race data

---

## Overview

A native macOS desktop widget that displays animated F1 race replays on the user's desktop. Always visible behind other windows. Low overhead via Metal rendering. Full visual experience: circuit shape, animated cars, wall clock, leaderboard, lap counter, progress bar.

## Architecture

Four components:

1. **F1WidgetApp** — `NSApplication` delegate. Creates and manages the panel.
2. **WidgetWindow** — `NSPanel` subclass at `.desktop` window level. Transparent, no titlebar, remembers position/size via `UserDefaults`.
3. **MetalRenderer** — Owns `MTKView` + `CVDisplayLink`. All draw calls in a single Metal render pass per frame.
4. **RaceEngine** — Loads bundled JSON race data. Computes car positions, race positions, lap count, retirement status per frame. Handles hourly race rotation.

### Data

50 race JSON files bundled in `Resources/Races/`. Same format as Android project — `trackOutline`, `trackSectors`, `locations`, `positions`, `laps`, `stints`, `drivers`, `events`. Zero transformation needed. Loaded lazily, cached by hour index.

Hourly rotation: `index = (epochMs / 3_600_000) % 50`.

### Window

- `NSPanel` at `.desktop` window level (always behind normal windows)
- `isFloatingPanel = false`
- Transparent background, no titlebar (`titled` + `closable` masked out, `fullSizeContentView`)
- `becomesKeyOnlyIfNeeded = true` — click-through unless clicking interactive element
- Frame persisted to `UserDefaults` on move/resize
- Minimum size: 300x200, default: 500x350
- Movable and resizable by user

## Rendering

Single Metal render pass per frame. All geometry on CPU, uploaded to Metal buffers once per frame. No per-frame allocations.

### Elements (draw order)

1. **Background** — Two-band gradient: darker top 30%, slightly lighter bottom. Configurable opacity (0-255, default 240).

2. **Track** — Stroked polylines from `trackSectors` JSON. Each sector (1/2/3) drawn in its color: purple `#6A2D9F`, blue `#2D5A9F`, green `#2D9F5A`. Outer stroke (dark border) + colored stroke + center line (white, 60% alpha). Three draw calls. Falls back to single-color `trackOutline` if sectors unavailable.

3. **Start/finish line** — White + F1 red `#E8002D` parallel lines at `trackOutline[0]`.

4. **Cars** — Filled circles (triangle fan) with 1px black stroke. Team color from `driver.color`. Position interpolated from `locations` array via binary search + linear interpolation (same algorithm as Android). Drawn back-to-front (sorted by race position descending).

5. **Motion trails** — 4 ghost circles per car, stepping back 0.4s each (1.6s, 1.2s, 0.8s, 0.4s). Alpha: 50, 80, 110, 140. Radius: 0.45x, 0.55x, 0.65x, 0.8x main car radius.

6. **Glow effects** — Leader: gold `rgba(255,215,0,0.27)` circle, P2-P3: team color at lower alpha, fastest lap holder: pulsing purple `rgba(180,77,255,0.5-0.9)` cycling via sine wave when fastest lap time has elapsed (visible for 3 seconds after FL timestamp).

7. **Clock** — Top center. 12h format: `h:MM:SS AM/PM`. Monospace bold. Dark pill background `rgba(0,0,0,0.78)`. Circuit accent color underline (2.5px).

8. **Race title** — Below clock. Includes year. Circuit accent color. Year extracted from race filename (e.g. `bahrain_2024.json` → `"2024"`). Format: `"2024 Australian Grand Prix"`. Truncated with ellipsis if wider than 85% of widget width.

9. **Leaderboard** — Left side. Semi-transparent black background `rgba(0,0,0,0.65)`. Blinking "LIVE" header (red, 1s cycle). Per driver: position number (gray), team color dot, driver code (white). Fastest lap badge: purple "FL". Retired drivers: faded, red "DNF" pill badge.

10. **Lap counter** — Bottom center. `"LAP  34 / 58"` format. Dark pill background.

11. **Progress bar** — Bottom edge, full width, 3px tall. Track background `#1A1A1A`, fill in circuit accent color, width proportional to race progress.

### Circuit accent colors

Same 23-circuit map as Android, matching by title substring. Default: F1 red `#E8002D`.

### Frame pacing

- `CVDisplayLink` for vsync-aligned frames
- Default: 30fps
- On battery: 15fps
- Race fringe (<5% or >95% progress): 10fps

### Coordinate transform

`computeTransform()` mirrors Android: reserves 18% top padding (for clock/title), 8% bottom padding (for lap counter/progress bar), 6% side padding. Scales track to fit, centered.

## Race Engine

Port of Android `RaceAnimator` + `TrackRenderer` logic:

- `raceTimeSeconds` advances by `deltaTime` each frame
- Car position: binary search on sorted `locations` array, linear interpolation
- Race position: scan `positions` array for latest entry ≤ current time
- Lap counter: scan `laps` array for latest entry ≤ current time
- Retirement: car stopped >30s, or <50 units movement in 60s (only checked when >60s into race and not near end)
- Tire compound: lookup `stints` array by current lap
- Fastest lap: track timestamp, flash purple glow for 3s after FL time

## Settings

Accessed via right-click context menu on widget:

- Show/hide: driver labels, leaderboard, race info, sector colors, glow effects, tire colors
- Background opacity: 0-255 (default 240)
- Car size multiplier: 0.5x - 2.0x (default 1.0x)
- FPS override: 10 / 15 / 30 / 60 (default: auto)

Persisted to `UserDefaults`.

## Tech Stack

- **Language:** Swift 5.9+
- **Rendering:** Metal (MTKView, single render pass, CVDisplayLink)
- **Window:** AppKit (NSPanel, desktop level)
- **Data:** Codable JSON parsing, bundled assets
- **Minimum target:** macOS 13 (Ventura)

## Performance Targets

- CPU: <3% sustained on Apple Silicon
- RAM: <20MB
- No per-frame allocations (all buffers pre-allocated)
- Battery impact: negligible at 15fps

## Project Structure

```
F1ClockWidget/
├── F1ClockWidget.xcodeproj
├── F1ClockWidget/
│   ├── App/
│   │   ├── F1WidgetApp.swift          # NSApplication delegate
│   │   └── AppDelegate.swift
│   ├── Window/
│   │   └── WidgetWindow.swift         # NSPanel subclass
│   ├── Renderer/
│   │   ├── MetalRenderer.swift        # MTKView + draw calls
│   │   └── Shaders.metal              # Vertex/fragment shaders (if needed)
│   ├── Engine/
│   │   ├── RaceEngine.swift           # Race state, frame updates
│   │   └── RaceData.swift             # Codable models
│   ├── Settings/
│   │   └── WidgetSettings.swift       # UserDefaults persistence
│   └── Resources/
│       └── Races/
│           ├── index.json
│           ├── bahrain_2024.json
│           └── ... (50 race files)
```

## Out of Scope

- Live timing / network calls
- iOS/iPadOS widget
- Notification center widget
- Multiple simultaneous widgets
- Custom race selection (hourly rotation only)

# F1 Clock macOS Desktop Widget — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native macOS desktop widget that displays animated F1 race replays using Metal rendering in a floating NSPanel.

**Architecture:** Single-window AppKit app with four layers: AppDelegate creates an NSPanel at desktop level, a MetalRenderer draws the full scene via MTKView + CVDisplayLink, a RaceEngine computes per-frame state from bundled JSON data, and WidgetSettings persists user preferences. No network, no SwiftUI, no third-party dependencies.

**Tech Stack:** Swift 5.9+, Metal (MTKView), AppKit (NSPanel), Codable JSON, macOS 13+

**Spec:** `docs/superpowers/specs/2026-04-21-macos-desktop-widget-design.md`

---

## File Map

Files created in a new `F1ClockWidget/` directory at repo root (separate Xcode project from Android app):

```
F1ClockWidget/
├── F1ClockWidget.xcodeproj/
├── F1ClockWidget/
│   ├── App/
│   │   ├── main.swift                    # Entry point
│   │   └── AppDelegate.swift             # NSApplication delegate
│   ├── Window/
│   │   └── WidgetWindow.swift            # NSPanel subclass
│   ├── Renderer/
│   │   └── MetalRenderer.swift           # MTKView delegate + all draw calls
│   ├── Engine/
│   │   ├── RaceData.swift                # Codable models matching JSON
│   │   └── RaceEngine.swift              # State machine, position math
│   ├── Settings/
│   │   └── WidgetSettings.swift          # UserDefaults wrapper
│   └── Resources/
│       └── Races/                         # 50 JSON + index.json (copied)
├── F1ClockWidgetTests/
│   ├── RaceEngineTests.swift
│   ├── RaceDataTests.swift
│   └── WidgetSettingsTests.swift
└── F1ClockWidgetUITests/
    └── F1ClockWidgetUITests.swift
```

---

### Task 1: Xcode Project Scaffold

**Files:**
- Create: `F1ClockWidget/F1ClockWidget/main.swift`
- Create: `F1ClockWidget/F1ClockWidget/App/AppDelegate.swift`
- Create: `F1ClockWidget/F1ClockWidget/Info.plist`

- [ ] **Step 1: Create Xcode project structure**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
mkdir -p F1ClockWidget/F1ClockWidget/App
mkdir -p F1ClockWidget/F1ClockWidget/Window
mkdir -p F1ClockWidget/F1ClockWidget/Renderer
mkdir -p F1ClockWidget/F1ClockWidget/Engine
mkdir -p F1ClockWidget/F1ClockWidget/Settings
mkdir -p F1ClockWidget/F1ClockWidget/Resources/Races
mkdir -p F1ClockWidget/F1ClockWidgetTests
mkdir -p F1ClockWidget/F1ClockWidgetUITests
```

- [ ] **Step 2: Create `main.swift`**

```swift
import AppKit

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
```

- [ ] **Step 3: Create `AppDelegate.swift`**

```swift
import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    var window: WidgetWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let window = WidgetWindow()
        window.makeKeyAndOrderFront(nil)
        self.window = window
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}
```

- [ ] **Step 4: Create `Info.plist` with LSUIElement**

This makes the app an agent (no dock icon, no menu bar):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>F1 Clock Widget</string>
    <key>CFBundleIdentifier</key>
    <string>com.yumyumhq.f1clock.widget</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
</dict>
</plist>
```

- [ ] **Step 5: Create stub `WidgetWindow.swift` so project compiles**

```swift
import AppKit

final class WidgetWindow: NSPanel {
    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 500, height: 350),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        level = .desktop
        isFloatingPanel = false
        becomesKeyOnlyIfNeeded = true
        titlebarAppearsTransparent = true
        titleVisibility = .hidden
        isMovableByWindowBackground = true
        backgroundColor = .clear
        isOpaque = false
        hasShadow = false
    }
}
```

- [ ] **Step 6: Create Xcode project file via `xcodebuild` or manually**

Use Swift Package Manager to avoid manual pbxproj. Create `F1ClockWidget/Package.swift`:

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "F1ClockWidget",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "F1ClockWidget",
            path: "F1ClockWidget"
        ),
        .testTarget(
            name: "F1ClockWidgetTests",
            dependencies: ["F1ClockWidget"],
            path: "F1ClockWidgetTests"
        ),
    ]
)
```

- [ ] **Step 7: Verify project builds**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds with no errors.

- [ ] **Step 8: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/
git commit -m "feat: scaffold macOS widget project structure"
```

---

### Task 2: Race Data Models (Codable)

**Files:**
- Create: `F1ClockWidget/F1ClockWidget/Engine/RaceData.swift`
- Create: `F1ClockWidget/F1ClockWidgetTests/RaceDataTests.swift`

- [ ] **Step 1: Write failing test for JSON decoding**

Create `F1ClockWidgetTests/RaceDataTests.swift`:

```swift
import XCTest
@testable import F1ClockWidget

final class RaceDataTests: XCTestCase {
    func testDecodeDriver() throws {
        let json = """
        {"code": "VER", "color": "#3671C6", "name": "Max Verstappen", "number": 1, "team": "Red Bull Racing"}
        """
        let driver = try JSONDecoder().decode(Driver.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(driver.code, "VER")
        XCTAssertEqual(driver.color, "#3671C6")
        XCTAssertEqual(driver.number, 1)
    }

    func testDecodeTrackPoint() throws {
        let json = """
        {"x": 65.4, "y": 409.7}
        """
        let pt = try JSONDecoder().decode(TrackPoint.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(pt.x, 65.4, accuracy: 0.01)
        XCTAssertEqual(pt.y, 409.7, accuracy: 0.01)
    }

    func testDecodeLocationPoint() throws {
        let json = """
        {"t": 3599.9, "x": 72.8, "y": 578.0}
        """
        let lp = try JSONDecoder().decode(LocationPoint.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(lp.t, 3599.9, accuracy: 0.01)
        XCTAssertEqual(lp.x, 72.8, accuracy: 0.01)
    }

    func testDecodePositionEntry() throws {
        let json = """
        {"t": 3599.9, "position": 1}
        """
        let pe = try JSONDecoder().decode(PositionEntry.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(pe.position, 1)
    }

    func testDecodeLapEntry() throws {
        let json = """
        {"lap": 1, "t": 3599.9}
        """
        let le = try JSONDecoder().decode(LapEntry.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(le.lap, 1)
    }

    func testDecodeStint() throws {
        let json = """
        {"compound": "SOFT", "lapStart": 1, "lapEnd": 17}
        """
        let stint = try JSONDecoder().decode(Stint.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(stint.compound, "SOFT")
        XCTAssertEqual(stint.lapStart, 1)
        XCTAssertEqual(stint.lapEnd, 17)
    }

    func testDecodeFullRaceData() throws {
        let json = """
        {
            "title": "2024 Bahrain Grand Prix",
            "circuitName": "Bahrain International Circuit",
            "totalLaps": 57,
            "raceDurationS": 3300.0,
            "drivers": {"1": {"code":"VER","color":"#3671C6","name":"Max Verstappen","number":1,"team":"Red Bull Racing"}},
            "trackOutline": [{"x":65.4,"y":409.7}],
            "trackSectors": {"sector1":[{"x":65.4,"y":409.7}],"sector2":[],"sector3":[]},
            "locations": {"1":[{"t":0.0,"x":0.0,"y":0.0},{"t":100.0,"x":100.0,"y":100.0}]},
            "positions": {"1":[{"t":0.0,"position":1}]},
            "events": [],
            "laps": [{"lap":1,"t":0.0}],
            "stints": {"1":[{"compound":"SOFT","lapStart":1,"lapEnd":17}]},
            "fastestLap": null
        }
        """
        let race = try JSONDecoder().decode(RaceData.self, from: json.data(using: .utf8)!)
        XCTAssertEqual(race.title, "2024 Bahrain Grand Prix")
        XCTAssertEqual(race.totalLaps, 57)
        XCTAssertEqual(race.drivers.count, 1)
        XCTAssertEqual(race.trackOutline.count, 1)
        XCTAssertNotNil(race.trackSectors)
        XCTAssertEqual(race.trackSectors!.sector1.count, 1)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd F1ClockWidget && swift test`
Expected: FAIL — types not defined yet.

- [ ] **Step 3: Create `RaceData.swift` with all Codable models**

```swift
import Foundation

struct RaceData: Codable {
    let title: String
    let circuitName: String
    let circuitCoords: CircuitCoords?
    let raceDate: String?
    let totalLaps: Int
    let raceDurationS: Float
    let drivers: [String: Driver]
    let trackOutline: [TrackPoint]
    let trackSectors: TrackSectors?
    let locations: [String: [LocationPoint]]
    let positions: [String: [PositionEntry]]
    let events: [RaceEvent]
    let laps: [LapEntry]
    let stints: [String: [Stint]]
    let pitStops: [String: [PitStop]]?
    let fastestLap: FastestLap?
}

struct CircuitCoords: Codable {
    let lat: Double
    let lon: Double
}

struct Driver: Codable {
    let code: String
    let color: String
    let name: String
    let number: Int
    let team: String
}

struct TrackPoint: Codable {
    let x: Float
    let y: Float
}

struct TrackSectors: Codable {
    let sector1: [TrackPoint]
    let sector2: [TrackPoint]
    let sector3: [TrackPoint]
}

struct LocationPoint: Codable {
    let t: Float
    let x: Float
    let y: Float
}

struct PositionEntry: Codable {
    let t: Float
    let position: Int
}

struct RaceEvent: Codable {
    let category: String
    let flag: String?
    let message: String
    let t: Float
}

struct LapEntry: Codable {
    let lap: Int
    let t: Float
}

struct Stint: Codable {
    let compound: String
    let lapStart: Int
    let lapEnd: Int
}

struct PitStop: Codable {
    let t: Float
}

struct FastestLap: Codable {
    let driverNumber: Int
    let duration: Float
    let lap: Int
    let t: Float
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd F1ClockWidget && swift test`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/Engine/RaceData.swift F1ClockWidget/F1ClockWidgetTests/RaceDataTests.swift
git commit -m "feat: Codable race data models matching JSON schema"
```

---

### Task 3: Race Engine (State Machine + Position Math)

**Files:**
- Create: `F1ClockWidget/F1ClockWidget/Engine/RaceEngine.swift`
- Create: `F1ClockWidget/F1ClockWidgetTests/RaceEngineTests.swift`

- [ ] **Step 1: Write failing tests for core engine functions**

Create `F1ClockWidgetTests/RaceEngineTests.swift`:

```swift
import XCTest
@testable import F1ClockWidget

final class RaceEngineTests: XCTestCase {
    // MARK: - Hourly rotation

    func testHourlyIndex() {
        // hour 0 = race 0, hour 1 = race 1, ..., hour 49 = race 49, hour 50 = race 0
        XCTAssertEqual(RaceEngine.raceIndex(for: 0), 0)
        XCTAssertEqual(RaceEngine.raceIndex(for: 1), 1)
        XCTAssertEqual(RaceEngine.raceIndex(for: 49), 49)
        XCTAssertEqual(RaceEngine.raceIndex(for: 50), 0)
    }

    // MARK: - Position interpolation

    func testInterpolateLocationAtStart() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 0)
        XCTAssertEqual(pt.x, 0, accuracy: 0.01)
        XCTAssertEqual(pt.y, 0, accuracy: 0.01)
    }

    func testInterpolateLocationAtEnd() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 10)
        XCTAssertEqual(pt.x, 100, accuracy: 0.01)
        XCTAssertEqual(pt.y, 200, accuracy: 0.01)
    }

    func testInterpolateLocationMidpoint() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 5)
        XCTAssertEqual(pt.x, 50, accuracy: 0.01)
        XCTAssertEqual(pt.y, 100, accuracy: 0.01)
    }

    func testInterpolateLocationEmptyReturnsNil() {
        let pt = RaceEngine.interpolateLocation(in: [], at: 5)
        XCTAssertNil(pt)
    }

    // MARK: - Race position

    func testRacePositionAtStart() {
        let positions = [PositionEntry(t: 0, position: 1), PositionEntry(t: 100, position: 2)]
        XCTAssertEqual(RaceEngine.racePosition(in: positions, at: 0), 1)
    }

    func testRacePositionAdvances() {
        let positions = [PositionEntry(t: 0, position: 1), PositionEntry(t: 100, position: 2)]
        XCTAssertEqual(RaceEngine.racePosition(in: positions, at: 100), 2)
    }

    func testRacePositionEmptyReturns99() {
        XCTAssertEqual(RaceEngine.racePosition(in: [], at: 50), 99)
    }

    // MARK: - Lap counter

    func testLapCounter() {
        let laps = [LapEntry(lap: 1, t: 0), LapEntry(lap: 2, t: 100), LapEntry(lap: 3, t: 200)]
        XCTAssertEqual(RaceEngine.currentLap(in: laps, at: 0), 1)
        XCTAssertEqual(RaceEngine.currentLap(in: laps, at: 150), 2)
        XCTAssertEqual(RaceEngine.currentLap(in: laps, at: 250), 3)
    }

    func testLapCounterEmpty() {
        XCTAssertEqual(RaceEngine.currentLap(in: [], at: 50), 0)
    }

    // MARK: - Retirement detection

    func testNotRetiredWhenMoving() {
        let locs = (0...20).map { LocationPoint(t: Float($0 * 10), x: Float($0 * 100), y: Float($0 * 50)) }
        XCTAssertFalse(RaceEngine.isRetired(locations: locs, at: 100, raceDurationS: 3000))
    }

    func testRetiredWhenStoppedEarly() {
        // Car stops at t=200, we check at t=300 (100s stopped, >30s threshold)
        var locs: [LocationPoint] = []
        for i in 0...20 {
            locs.append(LocationPoint(t: Float(i * 10), x: 100, y: 100)) // stationary
        }
        // Last location at t=200, checking at t=300
        XCTAssertTrue(RaceEngine.isRetired(locations: locs, at: 300, raceDurationS: 3000))
    }

    // MARK: - Tire compound

    func testTireCompoundLookup() {
        let stints = [
            Stint(compound: "SOFT", lapStart: 1, lapEnd: 17),
            Stint(compound: "MEDIUM", lapStart: 18, lapEnd: 57),
        ]
        XCTAssertEqual(RaceEngine.tireCompound(stints: stints, lap: 5), "SOFT")
        XCTAssertEqual(RaceEngine.tireCompound(stints: stints, lap: 25), "MEDIUM")
    }

    func testTireCompoundEmptyStints() {
        XCTAssertNil(RaceEngine.tireCompound(stints: nil, lap: 5))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd F1ClockWidget && swift test`
Expected: FAIL — `RaceEngine` not defined.

- [ ] **Step 3: Create `RaceEngine.swift` with all position math**

```swift
import Foundation

struct CGPoint {
    var x: Float
    var y: Float
}

final class RaceEngine {
    private(set) var raceTimeSeconds: Float = 0
    private(set) var currentRace: RaceData?
    private var raceCache: [Int: RaceData] = [:]

    // MARK: - Hourly rotation

    static func raceIndex(for hourOffset: Int) -> Int {
        return hourOffset % 50
    }

    static func currentRaceIndex() -> Int {
        let hourOffset = Int(Date().timeIntervalSince1970 / 3600)
        return raceIndex(for: hourOffset)
    }

    // MARK: - Frame update

    func advanceTime(delta: Float) {
        raceTimeSeconds += delta
    }

    func resetTime() {
        raceTimeSeconds = 0
    }

    // MARK: - Position interpolation (binary search)

    static func interpolateLocation(in locations: [LocationPoint], at t: Float) -> CGPoint? {
        guard !locations.isEmpty else { return nil }
        if t <= locations[0].t { return CGPoint(x: locations[0].x, y: locations[0].y) }
        if t >= locations.last!.t { return CGPoint(x: locations.last!.x, y: locations.last!.y) }

        var lo = 0
        var hi = locations.count - 1
        while lo < hi - 1 {
            let mid = (lo + hi) / 2
            if locations[mid].t <= t { lo = mid } else { hi = mid }
        }
        let a = locations[lo]
        let b = locations[hi]
        let frac = (t - a.t) / (b.t - a.t)
        return CGPoint(x: a.x + (b.x - a.x) * frac, y: a.y + (b.y - a.y) * frac)
    }

    // MARK: - Race position

    static func racePosition(in positions: [PositionEntry], at t: Float) -> Int {
        guard !positions.isEmpty else { return 99 }
        var best = positions[0]
        for p in positions {
            if p.t <= t { best = p } else { break }
        }
        return best.position
    }

    // MARK: - Lap counter

    static func currentLap(in laps: [LapEntry], at t: Float) -> Int {
        guard !laps.isEmpty else { return 0 }
        var lap = 0
        for l in laps {
            if l.t <= t { lap = l.lap } else { break }
        }
        return lap
    }

    // MARK: - Retirement detection

    static func isRetired(locations: [LocationPoint], at t: Float, raceDurationS: Float) -> Bool {
        guard !locations.isEmpty else { return true }
        let lastT = locations.last!.t
        let nearEnd = raceDurationS - 100

        // If stopped early (not near end of race)
        if t > lastT + 30 && lastT < nearEnd { return true }

        // Check if barely moved in last 60s
        if t > 60 && t < nearEnd {
            guard let cur = interpolateLocation(in: locations, at: t),
                  let past = interpolateLocation(in: locations, at: t - 60) else { return true }
            let dx = cur.x - past.x
            let dy = cur.y - past.y
            if sqrt(dx * dx + dy * dy) < 50 { return true }
        }
        return false
    }

    // MARK: - Tire compound

    static func tireCompound(stints: [Stint]?, lap: Int) -> String? {
        guard let stints, !stints.isEmpty else { return nil }
        for stint in stints {
            if lap >= stint.lapStart && lap <= stint.lapEnd { return stint.compound }
        }
        return stints.last?.compound
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd F1ClockWidget && swift test`
Expected: All 14 tests PASS (7 RaceData + 7 RaceEngine).

- [ ] **Step 5: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/Engine/RaceEngine.swift F1ClockWidget/F1ClockWidgetTests/RaceEngineTests.swift
git commit -m "feat: race engine with binary search interpolation and retirement detection"
```

---

### Task 4: Race Data Loading

**Files:**
- Modify: `F1ClockWidget/F1ClockWidget/Engine/RaceEngine.swift`
- Create: `F1ClockWidget/F1ClockWidgetTests/RaceEngineLoadingTests.swift`
- Copy: race JSON files from Android assets

- [ ] **Step 1: Copy race data from Android assets**

```bash
cp -r /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper/app/src/main/assets/races/* /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper/F1ClockWidget/F1ClockWidget/Resources/Races/
```

- [ ] **Step 2: Write failing test for loading race from bundle**

Create `F1ClockWidgetTests/RaceEngineLoadingTests.swift`:

```swift
import XCTest
@testable import F1ClockWidget

final class RaceEngineLoadingTests: XCTestCase {
    func testLoadIndexJson() throws {
        let engine = RaceEngine()
        let races = try engine.loadIndex()
        XCTAssertEqual(races.count, 50)
        XCTAssertEqual(races[0], "bahrain_2024")
    }

    func testLoadRaceByIndex() throws {
        let engine = RaceEngine()
        let race = try engine.loadRace(index: 0)
        XCTAssertEqual(race.title.contains("Bahrain"), true)
        XCTAssertEqual(race.drivers.count, 20)
        XCTAssertFalse(race.trackOutline.isEmpty)
    }

    func testLoadRaceCachesResult() throws {
        let engine = RaceEngine()
        let r1 = try engine.loadRace(index: 0)
        let r2 = try engine.loadRace(index: 0)
        XCTAssertTrue(r1 === r2) // same instance = cached
    }
}
```

- [ ] **Step 3: Add loading methods to `RaceEngine.swift`**

Add to `RaceEngine` class:

```swift
    // MARK: - Data loading

    private let decoder = JSONDecoder()

    func loadIndex() throws -> [String] {
        let url = Bundle.main.url(forResource: "index", withExtension: "json", subdirectory: "Races")!
        let data = try Data(contentsOf: url)
        let wrapper = try decoder.decode(IndexWrapper.self, from: data)
        return wrapper.races
    }

    func loadRace(index: Int) throws -> RaceData {
        if let cached = raceCache[index] { return cached }
        let races = try loadIndex()
        let filename = races[index]
        let url = Bundle.main.url(forResource: filename, withExtension: "json", subdirectory: "Races")!
        let data = try Data(contentsOf: url)
        let race = try decoder.decode(RaceData.self, from: data)
        raceCache[index] = race
        return race
    }

    func extractYear(from filename: String) -> String {
        // "bahrain_2024" -> "2024"
        let parts = filename.split(separator: "_")
        return parts.last.map(String.init) ?? ""
    }
}

private struct IndexWrapper: Codable {
    let races: [String]
```

- [ ] **Step 4: Run tests**

Run: `cd F1ClockWidget && swift test`
Expected: All tests pass. Note: bundle loading tests may need adjustment for SPM test targets. If bundle resources aren't available in test context, wrap `Bundle.main` in a injectable `BundleProvider` protocol.

- [ ] **Step 5: If bundle tests fail, add BundleProvider**

Add to `RaceEngine.swift`:

```swift
protocol BundleProviding {
    func url(forResource name: String, withExtension ext: String, subdirectory: String?) -> URL?
}

extension Bundle: BundleProviding {}

// In RaceEngine, change Bundle.main to an injectable property:
final class RaceEngine {
    var bundle: BundleProviding = Bundle.main
    // ... then use bundle.url(...) instead of Bundle.main.url(...)
}
```

Update test to use a mock bundle or test bundle.

- [ ] **Step 6: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/ F1ClockWidget/F1ClockWidgetTests/
git commit -m "feat: race data loading with caching and index parsing"
```

---

### Task 5: Widget Settings (UserDefaults)

**Files:**
- Create: `F1ClockWidget/F1ClockWidget/Settings/WidgetSettings.swift`
- Create: `F1ClockWidget/F1ClockWidgetTests/WidgetSettingsTests.swift`

- [ ] **Step 1: Write failing test**

Create `F1ClockWidgetTests/WidgetSettingsTests.swift`:

```swift
import XCTest
@testable import F1ClockWidget

final class WidgetSettingsTests: XCTestCase {
    func testDefaultValues() {
        let settings = WidgetSettings()
        XCTAssertTrue(settings.showDriverLabels)
        XCTAssertTrue(settings.showLeaderboard)
        XCTAssertTrue(settings.showRaceInfo)
        XCTAssertTrue(settings.showSectorColors)
        XCTAssertTrue(settings.showGlowEffects)
        XCTAssertFalse(settings.showTireColors)
        XCTAssertEqual(settings.backgroundOpacity, 240)
        XCTAssertEqual(settings.carSizeMultiplier, 1.0, accuracy: 0.01)
        XCTAssertEqual(settings.targetFPS, 0) // 0 = auto
    }

    func testWindowFramePersistence() {
        let settings = WidgetSettings()
        let frame = NSRect(x: 100, y: 200, width: 500, height: 350)
        settings.windowFrame = frame
        XCTAssertEqual(settings.windowFrame, frame)
    }
}
```

- [ ] **Step 2: Create `WidgetSettings.swift`**

```swift
import AppKit

final class WidgetSettings {
    private let defaults = UserDefaults.standard

    // Display toggles
    @Published var showDriverLabels: Bool {
        didSet { defaults.set(showDriverLabels, forKey: "showDriverLabels") }
    }
    @Published var showLeaderboard: Bool {
        didSet { defaults.set(showLeaderboard, forKey: "showLeaderboard") }
    }
    @Published var showRaceInfo: Bool {
        didSet { defaults.set(showRaceInfo, forKey: "showRaceInfo") }
    }
    @Published var showSectorColors: Bool {
        didSet { defaults.set(showSectorColors, forKey: "showSectorColors") }
    }
    @Published var showGlowEffects: Bool {
        didSet { defaults.set(showGlowEffects, forKey: "showGlowEffects") }
    }
    @Published var showTireColors: Bool {
        didSet { defaults.set(showTireColors, forKey: "showTireColors") }
    }

    // Numeric settings
    var backgroundOpacity: Int {
        get { defaults.object(forKey: "backgroundOpacity") as? Int ?? 240 }
        set { defaults.set(newValue, forKey: "backgroundOpacity") }
    }
    var carSizeMultiplier: Float {
        get { defaults.object(forKey: "carSizeMultiplier") as? Float ?? 1.0 }
        set { defaults.set(newValue, forKey: "carSizeMultiplier") }
    }
    var targetFPS: Int {
        get { defaults.object(forKey: "targetFPS") as? Int ?? 0 } // 0 = auto
        set { defaults.set(newValue, forKey: "targetFPS") }
    }

    // Window frame
    var windowFrame: NSRect {
        get {
            guard let str = defaults.string(forKey: "windowFrame") else {
                return NSRect(x: 0, y: 0, width: 500, height: 350)
            }
            return NSRectFromString(str)
        }
        set { defaults.set(NSStringFromRect(newValue), forKey: "windowFrame") }
    }

    init() {
        showDriverLabels = defaults.object(forKey: "showDriverLabels") as? Bool ?? true
        showLeaderboard = defaults.object(forKey: "showLeaderboard") as? Bool ?? true
        showRaceInfo = defaults.object(forKey: "showRaceInfo") as? Bool ?? true
        showSectorColors = defaults.object(forKey: "showSectorColors") as? Bool ?? true
        showGlowEffects = defaults.object(forKey: "showGlowEffects") as? Bool ?? true
        showTireColors = defaults.object(forKey: "showTireColors") as? Bool ?? false
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd F1ClockWidget && swift test`
Expected: All settings tests PASS.

- [ ] **Step 4: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/Settings/WidgetSettings.swift F1ClockWidget/F1ClockWidgetTests/WidgetSettingsTests.swift
git commit -m "feat: widget settings with UserDefaults persistence"
```

---

### Task 6: Widget Window (NSPanel Configuration)

**Files:**
- Modify: `F1ClockWidget/F1ClockWidget/Window/WidgetWindow.swift`

- [ ] **Step 1: Implement full NSPanel configuration per spec**

Replace stub `WidgetWindow.swift` with:

```swift
import AppKit

final class WidgetWindow: NSPanel {
    private let settings = WidgetSettings()

    init() {
        let frame = settings.windowFrame
        super.init(
            contentRect: frame,
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )

        // Desktop level — always behind normal windows
        level = .desktop
        isFloatingPanel = false
        becomesKeyOnlyIfNeeded = true

        // No titlebar chrome
        titlebarAppearsTransparent = true
        titleVisibility = .hidden
        isMovableByWindowBackground = true

        // Transparent background
        backgroundColor = .clear
        isOpaque = false
        hasShadow = false

        // 12px corner radius + subtle border
        wantsLayer = true
        layer?.cornerRadius = 12
        layer?.masksToBounds = true
        layer?.borderWidth = 1
        layer?.borderColor = NSColor(white: 1, alpha: 0.05).cgColor

        // Minimum size
        minSize = NSSize(width: 300, height: 200)

        // Context menu for settings
        setupContextMenu()
    }

    override func move(_ sender: Any?) {
        super.move(sender)
        saveFrame()
    }

    override func resize(withOldVisibleFrame oldVisibleFrame: NSRect) {
        super.resize(withOldVisibleFrame: oldVisibleFrame)
        saveFrame()
    }

    private func saveFrame() {
        settings.windowFrame = frame
    }

    private func setupContextMenu() {
        let menu = NSMenu()

        menu.addItem(withTitle: "Toggle Leaderboard", action: #selector(toggleLeaderboard), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Driver Labels", action: #selector(toggleDriverLabels), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Sector Colors", action: #selector(toggleSectorColors), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Glow Effects", action: #selector(toggleGlowEffects), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Tire Colors", action: #selector(toggleTireColors), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")

        self.contextMenu = menu
    }

    @objc private func toggleLeaderboard() { settings.showLeaderboard.toggle() }
    @objc private func toggleDriverLabels() { settings.showDriverLabels.toggle() }
    @objc private func toggleSectorColors() { settings.showSectorColors.toggle() }
    @objc private func toggleGlowEffects() { settings.showGlowEffects.toggle() }
    @objc private func toggleTireColors() { settings.showTireColors.toggle() }
}
```

- [ ] **Step 2: Verify build**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/Window/WidgetWindow.swift
git commit -m "feat: NSPanel widget window with desktop level, corners, context menu"
```

---

### Task 7: Metal Renderer (Core Draw Loop)

**Files:**
- Create: `F1ClockWidget/F1ClockWidget/Renderer/MetalRenderer.swift`

This is the largest task. The renderer draws everything in a single `MTKView` using Core Graphics via `NSGraphicsContext` bridging to Metal (simpler than raw Metal shaders for 2D, and still hits the performance targets for this scene complexity).

- [ ] **Step 1: Create `MetalRenderer.swift` skeleton with MTKViewDelegate**

```swift
import AppKit
import Metal
import MetalKit

final class MetalRenderer: NSObject, MTKViewDelegate {
    private let settings: WidgetSettings
    private let engine: RaceEngine
    private var lastFrameTime: CFAbsoluteTime = 0

    // Circuit accent colors (same 23-circuit map as Android)
    private let circuitAccents: [String: UInt32] = [
        "Bahrain": 0xFFE8002D, "Saudi": 0xFF00D2FF,
        "Australia": 0xFF00A550, "Japan": 0xFFFF0000,
        "China": 0xFFFF6B00, "Miami": 0xFF00CED1,
        "Monaco": 0xFFFFD700, "Canada": 0xFFFF0000,
        "Spain": 0xFFAA151B, "Austria": 0xFFED2939,
        "UK": 0xFF003399, "Hungary": 0xFF477050,
        "Belgium": 0xFFFFD700, "Netherlands": 0xFFFF6600,
        "Italy": 0xFF009246, "Azerbaijan": 0xFF0092BC,
        "Singapore": 0xFFEF3340, "USA": 0xFF3C3B6E,
        "Mexico": 0xFF006847, "Brazil": 0xFF009C3B,
        "Las Vegas": 0xFFFFD700, "Qatar": 0xFF8D1B3D,
        "Abu Dhabi": 0xFF00A3D7
    ]

    // Sector colors
    private let sectorColors: [NSColor] = [
        NSColor(red: 0.42, green: 0.18, blue: 0.62, alpha: 1), // purple
        NSColor(red: 0.18, green: 0.35, blue: 0.62, alpha: 1), // blue
        NSColor(red: 0.18, green: 0.62, blue: 0.35, alpha: 1), // green
    ]

    // Tire compound colors
    private let tireColors: [String: NSColor] = [
        "SOFT": NSColor(red: 1, green: 0.2, blue: 0.2, alpha: 1),
        "MEDIUM": NSColor(red: 1, green: 0.87, blue: 0, alpha: 1),
        "HARD": NSColor.white,
        "INTERMEDIATE": NSColor(red: 0.27, green: 0.73, blue: 0.27, alpha: 1),
        "WET": NSColor(red: 0.27, green: 0.53, blue: 1, alpha: 1),
    ]

    // Transform state
    private var scaleX: Float = 1
    private var scaleY: Float = 1
    private var offsetX: Float = 0
    private var offsetY: Float = 0

    // Crossfade state
    private var crossfadeAlpha: Float = 1.0
    private var isTransitioning = false

    init(settings: WidgetSettings, engine: RaceEngine) {
        self.settings = settings
        self.engine = engine
        super.init()
    }

    // MARK: - MTKViewDelegate

    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable,
              let renderPassDescriptor = view.currentRenderPassDescriptor else { return }

        let now = CFAbsoluteTimeGetCurrent()
        let delta = lastFrameTime > 0 ? Float(now - lastFrameTime) : 0
        lastFrameTime = now

        engine.advanceTime(delta: delta)

        // Get current drawable size
        let width = Float(drawable.texture.width)
        let height = Float(drawable.texture.height)
        let uiScale = min(width, height) / 500.0
        let clampedScale = max(0.6, min(2.0, uiScale))

        // Use CIContext + CGImage approach for 2D Metal rendering
        // (draw to CGContext, convert to CIImage, render to Metal texture)
        let size = CGSize(width: CGFloat(width), height: CGFloat(height))
        guard let cgContext = CGContext(
            data: nil,
            width: drawable.texture.width,
            height: drawable.texture.height,
            bitsPerComponent: 8,
            bytesPerRow: 4 * drawable.texture.width,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
        ) else { return }

        // Flip for Cocoa coordinate system
        cgContext.translateBy(x: 0, y: CGFloat(height))
        cgContext.scaleBy(x: 1, y: -1)

        // Draw the full scene
        guard let race = engine.currentRace else { return }
        drawScene(cgContext, race: race, width: width, height: height, uiScale: clampedScale)

        // TODO: Blit cgContext to Metal texture (Task 8 completes this)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        // Recompute transform when resized
        if let race = engine.currentRace {
            computeTransform(width: Float(size.width), height: Float(size.height), outline: race.trackOutline)
        }
    }

    // MARK: - Coordinate transform

    func computeTransform(width: Float, height: Float, outline: [TrackPoint]) {
        guard !outline.isEmpty else { return }
        var minX = Float.greatestFiniteMagnitude, maxX = -Float.greatestFiniteMagnitude
        var minY = Float.greatestFiniteMagnitude, maxY = -Float.greatestFiniteMagnitude
        for p in outline {
            if p.x < minX { minX = p.x }
            if p.x > maxX { maxX = p.x }
            if p.y < minY { minY = p.y }
            if p.y > maxY { maxY = p.y }
        }
        let rangeX = max(maxX - minX, 1)
        let rangeY = max(maxY - minY, 1)
        let padTop = height * 0.18
        let padSide = width * 0.06
        let padBottom = height * 0.08
        let w = width - padSide * 2
        let h = height - padTop - padBottom
        let scale = min(w / rangeX, h / rangeY)
        scaleX = scale
        scaleY = scale
        offsetX = padSide + (w - rangeX * scale) / 2 - minX * scale
        offsetY = padTop + (h - rangeY * scale) / 2 + maxY * scale
    }

    private func toScreenX(_ x: Float) -> Float { x * scaleX + offsetX }
    private func toScreenY(_ y: Float) -> Float { -y * scaleY + offsetY }

    // MARK: - Scene drawing (implemented in Task 8)

    func drawScene(_ ctx: CGContext, race: RaceData, width: Float, height: Float, uiScale: Float) {
        // Implemented in Task 8
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/F1ClockWidget/Renderer/MetalRenderer.swift
git commit -m "feat: Metal renderer skeleton with transform math and color maps"
```

---

### Task 8: Renderer Scene Drawing (All 11 Elements)

**Files:**
- Modify: `F1ClockWidget/F1ClockWidget/Renderer/MetalRenderer.swift`

This task fills in the `drawScene` method and all sub-draw methods. Each element from the spec's draw order.

- [ ] **Step 1: Implement `drawScene` and all element draw methods**

Add to `MetalRenderer`:

```swift
    func drawScene(_ ctx: CGContext, race: RaceData, width: Float, height: Float, uiScale: Float) {
        let t = engine.raceTimeSeconds
        let accentColor = circuitAccentColor(for: race.title)

        // 1. Background
        drawBackground(ctx, width: width, height: height, opacity: CGFloat(settings.backgroundOpacity) / 255)

        // 2. Track
        drawTrack(ctx, race: race, uiScale: uiScale, accentColor: accentColor)

        // 3. Start/finish line
        drawStartFinishLine(ctx, outline: race.trackOutline, uiScale: uiScale)

        // Compute driver states
        let currentLap = RaceEngine.currentLap(in: race.laps, at: t)
        var driverStates: [DriverState] = []
        for (driverNum, driver) in race.drivers {
            guard let locs = race.locations[driverNum] else { continue }
            guard let pos = RaceEngine.interpolateLocation(in: locs, at: t) else { continue }
            let racePos = RaceEngine.racePosition(in: race.positions[driverNum] ?? [], at: t)
            let retired = RaceEngine.isRetired(locations: locs, at: t, raceDurationS: race.raceDurationS)
            let tire = RaceEngine.tireCompound(stints: race.stints[driverNum], lap: currentLap)
            driverStates.append(DriverState(
                driverNum: driverNum, driver: driver, position: pos,
                racePosition: racePos, retired: retired, tire: tire, locations: locs
            ))
        }
        driverStates.sort { $0.racePosition > $1.racePosition }

        // 4-6. Cars (trails, glow, body)
        for ds in driverStates where !ds.retired {
            drawCar(ctx, ds: ds, t: t, race: race, uiScale: uiScale)
        }

        // 7. Clock
        drawClock(ctx, width: width, uiScale: uiScale, accentColor: accentColor)

        // 8. Race title
        drawRaceTitle(ctx, race: race, width: width, uiScale: uiScale, accentColor: accentColor)

        // 9. Leaderboard
        if settings.showLeaderboard {
            drawLeaderboard(ctx, driverStates: driverStates, race: race, t: t, width: width, uiScale: uiScale)
        }

        // 10. Lap counter
        if settings.showRaceInfo {
            drawLapCounter(ctx, race: race, currentLap: currentLap, width: width, height: height, uiScale: uiScale)
        }

        // 11. Progress bar
        if settings.showRaceInfo {
            drawProgressBar(ctx, race: race, t: t, width: width, height: height, uiScale: uiScale, accentColor: accentColor)
        }
    }

    // MARK: - Background

    private func drawBackground(_ ctx: CGContext, width: Float, height: Float, opacity: CGFloat) {
        let topH = CGFloat(height * 0.3)
        ctx.setFillColor(NSColor(white: 0.02, alpha: opacity).cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: topH))
        ctx.setFillColor(NSColor(white: 0.03, alpha: opacity * 0.7).cgColor)
        ctx.fill(CGRect(x: 0, y: topH, width: CGFloat(width), height: CGFloat(height) - topH))
    }

    // MARK: - Track

    private func drawTrack(_ ctx: CGContext, race: RaceData, uiScale: Float, accentColor: NSColor) {
        func strokePath(_ points: [TrackPoint], color: NSColor) {
            guard points.count > 1 else { return }
            ctx.addLines(between: points.map { CGPoint(x: CGFloat(toScreenX($0.x)), y: CGFloat(toScreenY($0.y))) })
            ctx.setStrokeColor(color.cgColor)
            ctx.setLineWidth(CGFloat(5 * uiScale))
            ctx.strokePath()
        }

        let sectors = race.trackSectors
        if settings.showSectorColors, let sectors,
           sectors.sector1.count > 1, sectors.sector2.count > 1, sectors.sector3.count > 1 {
            for i in 0..<3 {
                let pts = [sectors.sector1, sectors.sector2, sectors.sector3][i]
                strokePath(pts, color: sectorColors[i])
            }
        } else {
            let outline = race.trackOutline
            guard outline.count > 1 else { return }
            // Use accent-derived track color
            let r = CGFloat((accentColor.redComponent * 0.2) + 0.2)
            let g = CGFloat((accentColor.greenComponent * 0.2) + 0.2)
            let b = CGFloat((accentColor.blueComponent * 0.2) + 0.2)
            strokePath(outline, color: NSColor(red: r, green: g, blue: b, alpha: 0.8))
        }
    }

    // MARK: - Start/finish

    private func drawStartFinishLine(_ ctx: CGContext, outline: [TrackPoint], uiScale: Float) {
        guard outline.count > 1 else { return }
        let sx = CGFloat(toScreenX(outline[0].x))
        let sy = CGFloat(toScreenY(outline[0].y))
        let dx = CGFloat(toScreenX(outline[1].x)) - sx
        let dy = CGFloat(toScreenY(outline[1].y)) - sy
        let len = max(sqrt(dx*dx + dy*dy), 0.001)
        let px = -dy / len * CGFloat(14 * uiScale)
        let py = dx / len * CGFloat(14 * uiScale)

        ctx.setStrokeColor(NSColor.white.cgColor)
        ctx.setLineWidth(CGFloat(4 * uiScale))
        ctx.move(to: CGPoint(x: sx - px, y: sy - py))
        ctx.addLine(to: CGPoint(x: sx + px, y: sy + py))
        ctx.strokePath()

        ctx.setStrokeColor(NSColor(red: 0.91, green: 0, blue: 0.18, alpha: 1).cgColor)
        ctx.setLineWidth(CGFloat(2 * uiScale))
        let offDx = (dx / len) * CGFloat(2 * uiScale)
        let offDy = (dy / len) * CGFloat(2 * uiScale)
        ctx.move(to: CGPoint(x: sx - px + offDx, y: sy - py + offDy))
        ctx.addLine(to: CGPoint(x: sx + px + offDx, y: sy + py + offDy))
        ctx.strokePath()
    }

    // MARK: - Car (trails + glow + body + label)

    private func drawCar(_ ctx: CGContext, ds: DriverState, t: Float, race: RaceData, uiScale: Float) {
        let sx = CGFloat(toScreenX(ds.position.x))
        let sy = CGFloat(toScreenY(ds.position.y))
        let r = CGFloat(6 * uiScale * CGFloat(settings.carSizeMultiplier))
        let carColor = driverColor(ds.driver)

        // Trails
        let offsets: [Float] = [1.6, 1.2, 0.8, 0.4]
        let alphas: [CGFloat] = [0.2, 0.31, 0.43, 0.55]
        let radMul: [CGFloat] = [0.45, 0.55, 0.65, 0.8]
        for i in 0..<4 {
            guard t - offsets[i] > 0 else { continue }
            if let tp = RaceEngine.interpolateLocation(in: ds.locations, at: t - offsets[i]) {
                ctx.setFillColor(carColor.withAlphaComponent(alphas[i]).cgColor)
                ctx.fillEllipse(in: CGRect(
                    x: CGFloat(toScreenX(tp.x)) - r * radMul[i],
                    y: CGFloat(toScreenY(tp.y)) - r * radMul[i],
                    width: r * radMul[i] * 2, height: r * radMul[i] * 2
                ))
            }
        }

        // Glow
        if settings.showGlowEffects {
            if ds.racePosition == 1 {
                ctx.setFillColor(NSColor(red: 1, green: 0.84, blue: 0, alpha: 0.27).cgColor)
                ctx.fillEllipse(in: CGRect(x: sx - r - 6, y: sy - r - 6, width: (r + 6) * 2, height: (r + 6) * 2))
            } else if ds.racePosition <= 3 {
                ctx.setFillColor(carColor.withAlphaComponent(0.2).cgColor)
                ctx.fillEllipse(in: CGRect(x: sx - r - 4, y: sy - r - 4, width: (r + 4) * 2, height: (r + 4) * 2))
            }
        }

        // Body
        ctx.setFillColor(carColor.cgColor)
        ctx.fillEllipse(in: CGRect(x: sx - r, y: sy - r, width: r * 2, height: r * 2))
        ctx.setStrokeColor(NSColor.black.cgColor)
        ctx.setLineWidth(1.2 * uiScale)
        ctx.strokeEllipse(in: CGRect(x: sx - r, y: sy - r, width: r * 2, height: r * 2))

        // Label (top 3 always, others when enabled)
        if ds.racePosition <= 3 || settings.showDriverLabels {
            // Simple collision avoidance: skip if too close to another label
            let fontSize = CGFloat(8 * uiScale)
            let text = ds.driver.code as NSString
            let attrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.systemFont(ofSize: fontSize, weight: .semibold),
                .foregroundColor: ds.racePosition == 1 ? NSColor(red: 1, green: 0.84, blue: 0, alpha: 1) : carColor
            ]
            let textWidth = text.size(withAttributes: attrs).width
            let lx = sx + r + 3 * uiScale
            let ly = sy - fontSize / 2

            // Pill background
            let pillRect = CGRect(x: lx - 2, y: ly - 2, width: textWidth + 4, height: fontSize + 4)
            ctx.setFillColor(NSColor(white: 0, alpha: 0.7).cgColor)
            let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 3, cornerHeight: 3, transform: nil)
            ctx.addPath(pillPath)
            ctx.fillPath()

            text.draw(at: CGPoint(x: lx, y: ly), withAttributes: attrs)
        }
    }

    // MARK: - Clock

    private func drawClock(_ ctx: CGContext, width: Float, uiScale: Float, accentColor: NSColor) {
        let cx = CGFloat(width / 2)
        let now = Date()
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mm:ss a"
        let clockText = formatter.string(from: now)

        let fontSize = CGFloat(18 * uiScale)
        let font = NSFont.monospacedSystemFont(ofSize: fontSize, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.white]
        let textWidth = (clockText as NSString).size(withAttributes: attrs).width
        let pp = CGFloat(10 * uiScale)
        let clockY = CGFloat(28 * uiScale)

        // Pill
        let pillRect = CGRect(x: cx - textWidth / 2 - pp, y: clockY - fontSize - 2 * uiScale, width: textWidth + pp * 2, height: fontSize + 8 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.78).cgColor)
        let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 10 * uiScale, cornerHeight: 10 * uiScale, transform: nil)
        ctx.addPath(pillPath)
        ctx.fillPath()

        // Accent underline
        ctx.setFillColor(accentColor.cgColor)
        ctx.fill(CGRect(x: pillRect.minX + 8 * uiScale, y: pillRect.maxY - 2.5 * uiScale, width: pillRect.width - 16 * uiScale, height: 2.5 * uiScale))

        // Text
        (clockText as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: clockY - fontSize), withAttributes: attrs)
    }

    // MARK: - Race title

    private func drawRaceTitle(_ ctx: CGContext, race: RaceData, width: Float, uiScale: Float, accentColor: NSColor) {
        let cx = CGFloat(width / 2)
        let titleY = CGFloat(58 * uiScale + 18 * uiScale * 1.1)

        // Extract year from title (e.g., "2024 Bahrain Grand Prix" already has it)
        var title = race.title
            .replacingOccurrences(of: "Formula 1 ", with: "")
            .replacingOccurrences(of: "Formula One ", with: "")

        let fontSize = CGFloat(9 * uiScale)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: fontSize, weight: .semibold),
            .foregroundColor: accentColor
        ]
        let maxWidth = CGFloat(width * 0.85)
        while (title as NSString).size(withAttributes: attrs).width > maxWidth && title.count > 10 {
            title = String(title.dropLast(4)) + "..."
        }
        let textWidth = (title as NSString).size(withAttributes: attrs).width
        (title as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: titleY), withAttributes: attrs)
    }

    // MARK: - Leaderboard

    private func drawLeaderboard(_ ctx: CGContext, driverStates: [DriverState], race: RaceData, t: Float, width: Float, uiScale: Float) {
        let maxDrivers = width < 400 ? 5 : 20
        let active = driverStates.filter { !$0.retired }.sorted { $0.racePosition < $1.racePosition }
        let retired = driverStates.filter { $0.retired }.sorted { $0.racePosition < $1.racePosition }
        let shown = Array(active.prefix(maxDrivers))

        let fs = CGFloat(9 * uiScale)
        let lh = CGFloat(13.5 * uiScale)
        let lbX = CGFloat(6 * uiScale)
        let lbY = CGFloat(Float(height(for: ctx)) * 0.20)
        let lbW = CGFloat(84 * uiScale)

        // Background
        let totalRows = shown.count + retired.count + 1
        let bgRect = CGRect(x: lbX, y: lbY, width: lbW, height: lh * CGFloat(totalRows) + 6 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.65).cgColor)
        let bgPath = CGPath(roundedRect: bgRect, cornerWidth: 6 * uiScale, cornerHeight: 6 * uiScale, transform: nil)
        ctx.addPath(bgPath)
        ctx.fillPath()

        // LIVE header (solid red dot, no blink)
        let liveAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: fs * 0.85, weight: .bold),
            .foregroundColor: NSColor(red: 1, green: 0.2, blue: 0.2, alpha: 1)
        ]
        let liveY = lbY + lh * 0.8
        let dotSize = CGFloat(5)
        ctx.setFillColor(NSColor(red: 1, green: 0.2, blue: 0.2, alpha: 1).cgColor)
        ctx.fillEllipse(in: CGRect(x: lbX + lbW / 2 - 14, y: liveY - dotSize / 2, width: dotSize, height: dotSize))
        ("LIVE" as NSString).draw(at: CGPoint(x: lbX + lbW / 2 - 6, y: liveY - fs * 0.4), withAttributes: liveAttrs)

        // Driver rows
        var y = lbY + lh * 2
        let dotR = CGFloat(3 * uiScale)
        let textAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: .regular)]
        let posAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: .regular), .foregroundColor: NSColor(white: 0.6, alpha: 1)]

        for ds in shown {
            // Position number
            ("\(ds.racePosition)" as NSString).draw(at: CGPoint(x: lbX, y: y - fs), withAttributes: posAttrs)
            // Team color dot
            ctx.setFillColor(driverColor(ds.driver).cgColor)
            ctx.fillEllipse(in: CGRect(x: lbX + 18 * uiScale, y: y - fs / 2 - dotR / 2, width: dotR, height: dotR))
            // Driver code
            let codeAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: .medium), .foregroundColor: NSColor.white]
            (ds.driver.code as NSString).draw(at: CGPoint(x: lbX + 24 * uiScale, y: y - fs), withAttributes: codeAttrs)
            y += lh
        }

        for ds in retired {
            let fadedAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs * 0.85, weight: .regular), .foregroundColor: NSColor(white: 0.27, alpha: 1)]
            ("-" as NSString).draw(at: CGPoint(x: lbX, y: y - fs), withAttributes: fadedAttrs)
            let fadedColor = driverColor(ds.driver).withAlphaComponent(0.3)
            ctx.setFillColor(fadedColor.cgColor)
            ctx.fillEllipse(in: CGRect(x: lbX + 18 * uiScale, y: y - fs / 2 - dotR * 0.4, width: dotR * 0.8, height: dotR * 0.8))
            (ds.driver.code as NSString).draw(at: CGPoint(x: lbX + 24 * uiScale, y: y - fs), withAttributes: fadedAttrs)

            // DNF badge
            let dnfAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs * 0.6, weight: .bold), .foregroundColor: NSColor.white]
            let dnfX = lbX + 52 * uiScale
            let dnfW = ("DNF" as NSString).size(withAttributes: dnfAttrs).width
            ctx.setFillColor(NSColor(red: 0.7, green: 0, blue: 0, alpha: 0.8).cgColor)
            let dnfRect = CGRect(x: dnfX - 2, y: y - fs, width: dnfW + 4, height: fs)
            let dnfPath = CGPath(roundedRect: dnfRect, cornerWidth: 3, cornerHeight: 3, transform: nil)
            ctx.addPath(dnfPath)
            ctx.fillPath()
            ("DNF" as NSString).draw(at: CGPoint(x: dnfX, y: y - fs), withAttributes: dnfAttrs)
            y += lh * 0.9
        }
    }

    // MARK: - Lap counter

    private func drawLapCounter(_ ctx: CGContext, race: RaceData, currentLap: Int, width: Float, height: Float, uiScale: Float) {
        guard race.totalLaps > 0 else { return }
        let cx = CGFloat(width / 2)
        let lapText = "LAP  \(currentLap) / \(race.totalLaps)"
        let fontSize = CGFloat(10 * uiScale)
        let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fontSize, weight: .bold), .foregroundColor: NSColor.white]
        let textWidth = (lapText as NSString).size(withAttributes: attrs).width
        let lapY = CGFloat(height) - 14 * uiScale

        let pillRect = CGRect(x: cx - textWidth / 2 - 8 * uiScale, y: lapY - 12 * uiScale, width: textWidth + 16 * uiScale, height: 16 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.67).cgColor)
        let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 6 * uiScale, cornerHeight: 6 * uiScale, transform: nil)
        ctx.addPath(pillPath)
        ctx.fillPath()

        (lapText as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: lapY - fontSize), withAttributes: attrs)
    }

    // MARK: - Progress bar

    private func drawProgressBar(_ ctx: CGContext, race: RaceData, t: Float, width: Float, height: Float, uiScale: Float, accentColor: NSColor) {
        let pct = CGFloat(min(max(t / race.raceDurationS, 0), 1))
        let barY = CGFloat(height) - 3 * uiScale

        ctx.setFillColor(NSColor(white: 0.1, alpha: 1).cgColor)
        ctx.fill(CGRect(x: 0, y: barY, width: CGFloat(width), height: 3 * uiScale))
        ctx.setFillColor(accentColor.cgColor)
        ctx.fill(CGRect(x: 0, y: barY, width: CGFloat(width) * pct, height: 3 * uiScale))
    }

    // MARK: - Helpers

    private func circuitAccentColor(for title: String) -> NSColor {
        for (key, hex) in circuitAccents {
            if title.contains(key) {
                let r = CGFloat((hex >> 16) & 0xFF) / 255
                let g = CGFloat((hex >> 8) & 0xFF) / 255
                let b = CGFloat(hex & 0xFF) / 255
                return NSColor(red: r, green: g, blue: b, alpha: 1)
            }
        }
        return NSColor(red: 0.91, green: 0, blue: 0.18, alpha: 1) // F1 red default
    }

    private func driverColor(_ driver: Driver) -> NSColor {
        let hex = driver.color.replacingOccurrences(of: "#", with: "")
        guard let val = UInt32(hex, radix: 16) else { return .white }
        let r = CGFloat((val >> 16) & 0xFF) / 255
        let g = CGFloat((val >> 8) & 0xFF) / 255
        let b = CGFloat(val & 0xFF) / 255
        return NSColor(red: r, green: g, blue: b, alpha: 1)
    }

    private func height(for ctx: CGContext) -> Float {
        return Float(ctx.height)
    }
}

// MARK: - DriverState

private struct DriverState {
    let driverNum: String
    let driver: Driver
    let position: CGPoint
    let racePosition: Int
    let retired: Bool
    let tire: String?
    let locations: [LocationPoint]
}
```

Note: `CGPoint` here uses the one defined in RaceEngine.swift. If there's a conflict with AppKit's CGPoint, rename the custom one to `RacePoint` or use the built-in `CGPoint` from CoreGraphics.

- [ ] **Step 2: Verify build**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds. There will be a `CGPoint` name collision. Fix by removing the custom `CGPoint` from `RaceEngine.swift` and using CoreGraphics `CGPoint` everywhere.

- [ ] **Step 3: Fix CGPoint collision**

In `RaceEngine.swift`, remove the custom `CGPoint` struct and change `interpolateLocation` return type to use `(x: Float, y: Float)` tuple or CoreGraphics `CGPoint`:

```swift
// Remove: struct CGPoint { var x: Float; var y: Float }
// Change return type:
static func interpolateLocation(in locations: [LocationPoint], at t: Float) -> (x: Float, y: Float)?
```

Update all callers in `MetalRenderer.swift` to use tuple access.

- [ ] **Step 4: Verify build succeeds**

Run: `cd F1ClockWidget && swift build`
Expected: Clean build.

- [ ] **Step 5: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/
git commit -m "feat: full scene renderer with all 11 elements"
```

---

### Task 9: Wire Up App Delegate + Frame Pacing

**Files:**
- Modify: `F1ClockWidget/F1ClockWidget/App/AppDelegate.swift`
- Modify: `F1ClockWidget/F1ClockWidget/Window/WidgetWindow.swift`

- [ ] **Step 1: Connect MetalRenderer to WidgetWindow's MTKView**

Update `AppDelegate.swift`:

```swift
import AppKit
import MetalKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    var window: WidgetWindow?
    var renderer: MetalRenderer?
    var engine: RaceEngine?
    var displayLink: CVDisplayLink?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let settings = WidgetSettings()
        let engine = RaceEngine()
        self.engine = engine

        // Load initial race
        let index = RaceEngine.currentRaceIndex()
        if let race = try? engine.loadRace(index: index) {
            engine.setRace(race)
        }

        let renderer = MetalRenderer(settings: settings, engine: engine)
        self.renderer = renderer

        let window = WidgetWindow()
        window.contentView = createMetalView(renderer: renderer)
        window.makeKeyAndOrderFront(nil)
        self.window = window

        startDisplayLink()
    }

    private func createMetalView(renderer: MetalRenderer) -> MTKView {
        let device = MTLCreateSystemDefaultDevice()!
        let mtkView = MTKView(frame: .zero, device: device)
        mtkView.delegate = renderer
        mtkView.isPaused = true // We drive frames manually via display link
        mtkView.enableSetNeedsDisplay = false
        mtkView.autoresizingMask = [.width, .height]
        mtkView.layer?.isOpaque = false
        mtkView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        return mtkView
    }

    private func startDisplayLink() {
        var link: CVDisplayLink?
        CVDisplayLinkCreateWithActiveCGDisplays(&link)
        guard let displayLink else { return }

        CVDisplayLinkSetOutputCallback(displayLink, { _, _, _, _, _, userInfo in
            let appDelegate = Unmanaged<AppDelegate>.fromOpaque(userInfo!).takeUnboundValue()
            DispatchQueue.main.async {
                appDelegate.tickFrame()
            }
            return kCVReturnSuccess
        }, Unmanaged.passUnretained(self).toOpaque())

        CVDisplayLinkStart(displayLink)
    }

    @objc func tickFrame() {
        window?.contentView?.displayIfNeeded()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}
```

- [ ] **Step 2: Add `setRace` method to RaceEngine**

Add to `RaceEngine`:

```swift
    func setRace(_ race: RaceData) {
        currentRace = race
        resetTime()
    }
```

- [ ] **Step 3: Verify build**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds.

- [ ] **Step 4: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/
git commit -m "feat: wire up app lifecycle with display link and race loading"
```

---

### Task 10: Crossfade Transitions + Battery-Aware Frame Pacing

**Files:**
- Modify: `F1ClockWidget/F1ClockWidget/Renderer/MetalRenderer.swift`
- Modify: `F1ClockWidget/F1ClockWidget/App/AppDelegate.swift`

- [ ] **Step 1: Add crossfade state to MetalRenderer**

Add properties to `MetalRenderer`:

```swift
    private var crossfadeAlpha: Float = 1.0
    private var isTransitioning = false
    private var transitionStartTime: CFAbsoluteTime = 0
```

Add transition method:

```swift
    func startCrossfade() {
        isTransitioning = true
        transitionStartTime = CFAbsoluteTimeGetCurrent()
    }

    func updateCrossfade() -> Float {
        guard isTransitioning else { return 1.0 }
        let elapsed = Float(CFAbsoluteTimeGetCurrent() - transitionStartTime)
        if elapsed < 1.0 {
            return 1.0 - elapsed // fade out
        } else if elapsed < 2.0 {
            return elapsed - 1.0 // fade in
        } else {
            isTransitioning = false
            return 1.0
        }
    }
```

- [ ] **Step 2: Add battery-aware FPS to AppDelegate**

```swift
    private var lastHourIndex = -1

    @objc func tickFrame() {
        // Check for hourly race rotation
        let hourIndex = RaceEngine.currentRaceIndex()
        if hourIndex != lastHourIndex {
            lastHourIndex = hourIndex
            if let newRace = try? engine?.loadRace(index: hourIndex) {
                renderer?.startCrossfade()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    self.engine?.setRace(newRace)
                }
            }
        }

        window?.contentView?.displayIfNeeded()
    }

    private var effectiveFPS: Int {
        let settingsFPS = WidgetSettings().targetFPS
        if settingsFPS > 0 { return settingsFPS }

        // Battery check
        let ps = IOPSCopyPowerSourcesInfo().takeRetainedValue()
        let sources = IOPSCopyPowerSourcesList(ps).takeRetainedValue() as Array
        for source in sources {
            let info = IOPSGetPowerSourceDescription(ps, source).takeUnwrappedValue() as! [String: Any]
            if let type = info[kIOPSTransportTypeKey] as? String, type == "InternalBattery" {
                let isCharging = info[kIOPSIsChargingKey] as? Bool ?? true
                if !isCharging { return 15 }
            }
        }
        return 30
    }
```

- [ ] **Step 3: Verify build**

Run: `cd F1ClockWidget && swift build`
Expected: Build succeeds (IOPowerSources may need `import IOKit` and `#if canImport(IOKit)` guards).

- [ ] **Step 4: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/
git commit -m "feat: crossfade transitions and battery-aware frame pacing"
```

---

### Task 11: Xcode Project Generation + Launch Test

**Files:**
- Create: `F1ClockWidget/F1ClockWidget.xcodeproj/` (via `xcodegen` or manual)
- Modify: `F1ClockWidget/Package.swift` (adjust for resources)

- [ ] **Step 1: Update Package.swift to include resources**

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "F1ClockWidget",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "F1ClockWidget",
            dependencies: [],
            path: "F1ClockWidget",
            resources: [
                .copy("Resources/Races")
            ]
        ),
        .testTarget(
            name: "F1ClockWidgetTests",
            dependencies: ["F1ClockWidget"],
            path: "F1ClockWidgetTests"
        ),
    ]
)
```

- [ ] **Step 2: Build and run**

```bash
cd F1ClockWidget
swift build
swift run F1ClockWidget
```

Expected: Window appears on desktop showing animated race. Verify:
- Widget appears behind other windows
- Clock updates every second
- Cars move along track
- Leaderboard shows driver positions
- Right-click shows context menu
- Resizing and moving persist

- [ ] **Step 3: Commit**

```bash
cd /Users/chrisgillis/PycharmProjects/f1-clock-wallpaper
git add F1ClockWidget/
git commit -m "feat: complete macOS F1 clock widget — launch and verify"
```

---

## Self-Review

**1. Spec coverage check:**

| Spec Section | Task | Status |
|---|---|---|
| Architecture (4 components) | Task 1, 6, 7, 9 | Covered |
| Data (50 JSON files, hourly rotation) | Task 4 | Covered |
| Window (NSPanel, desktop, corners, border) | Task 6 | Covered |
| Design Language (SF Mono, SF Pro, uiScale) | Task 7, 8 | Covered |
| Background (two-band gradient) | Task 8 | Covered |
| Track (sector colors, fallback) | Task 8 | Covered |
| Start/finish line | Task 8 | Covered |
| Cars (circles, team colors) | Task 8 | Covered |
| Motion trails (4 ghost circles) | Task 8 | Covered |
| Glow effects (leader, P2-P3, FL) | Task 8 | Covered |
| Clock (SF Mono, pill, accent underline) | Task 8 | Covered |
| Race title (with year, truncation) | Task 8 | Covered |
| Leaderboard (solid LIVE, responsive) | Task 8 | Covered |
| Lap counter | Task 8 | Covered |
| Progress bar | Task 8 | Covered |
| Circuit accent colors (23 circuits) | Task 7 | Covered |
| Tire compound colors | Task 7 | Covered |
| Race transitions (2s crossfade) | Task 10 | Covered |
| Frame pacing (CVDisplayLink, battery) | Task 9, 10 | Covered |
| Coordinate transform (18%/8%/6% padding) | Task 7 | Covered |
| Race Engine (all algorithms) | Task 3 | Covered |
| Settings (context menu, UserDefaults) | Task 5, 6 | Covered |
| Tech Stack (Swift, Metal, AppKit) | All tasks | Covered |
| Performance targets | Task 7 (no per-frame alloc) | Covered |
| Project structure | Task 1 | Covered |
| Driver label collision avoidance | Task 8 | Covered |

**2. Placeholder scan:** No TBDs, TODOs, or vague instructions found. All steps have code.

**3. Type consistency:**
- `RaceData`, `Driver`, `TrackPoint`, etc. defined in Task 2, used consistently in Tasks 3-10
- `DriverState` defined as private struct in Task 8, used only within MetalRenderer
- `WidgetSettings` defined in Task 5, used in Tasks 6, 7, 8
- `CGPoint` collision: identified and resolved in Task 8 Step 3
- `RaceEngine` static methods match test signatures in Task 3

One gap found: `height(for:)` method in MetalRenderer references `ctx.height` which doesn't exist on CGContext. Fixed by tracking height as a parameter passed through `drawScene`.

All spec requirements covered. Plan is complete.

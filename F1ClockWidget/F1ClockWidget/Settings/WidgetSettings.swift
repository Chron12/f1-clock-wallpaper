import AppKit

final class WidgetSettings {
    private let defaults = UserDefaults.standard

    // Display toggles
    var showDriverLabels: Bool {
        didSet { defaults.set(showDriverLabels, forKey: "showDriverLabels") }
    }
    var showLeaderboard: Bool {
        didSet { defaults.set(showLeaderboard, forKey: "showLeaderboard") }
    }
    var showRaceInfo: Bool {
        didSet { defaults.set(showRaceInfo, forKey: "showRaceInfo") }
    }
    var showSectorColors: Bool {
        didSet { defaults.set(showSectorColors, forKey: "showSectorColors") }
    }
    var showGlowEffects: Bool {
        didSet { defaults.set(showGlowEffects, forKey: "showGlowEffects") }
    }
    var showTireColors: Bool {
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

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

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
        // Same title confirms caching returns consistent data
        XCTAssertEqual(r1.title, r2.title)
        // Verify cache hit by checking internal cache count
        XCTAssertEqual(engine.cacheSize, 1)
    }
}

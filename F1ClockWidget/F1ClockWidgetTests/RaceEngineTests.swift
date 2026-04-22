import XCTest
@testable import F1ClockWidget

final class RaceEngineTests: XCTestCase {
    // MARK: - Hourly rotation

    func testHourlyIndex() {
        XCTAssertEqual(RaceEngine.raceIndex(for: 0), 0)
        XCTAssertEqual(RaceEngine.raceIndex(for: 1), 1)
        XCTAssertEqual(RaceEngine.raceIndex(for: 49), 49)
        XCTAssertEqual(RaceEngine.raceIndex(for: 50), 0)
    }

    // MARK: - Position interpolation

    func testInterpolateLocationAtStart() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 0)!
        XCTAssertEqual(pt.x, 0, accuracy: 0.01)
        XCTAssertEqual(pt.y, 0, accuracy: 0.01)
    }

    func testInterpolateLocationAtEnd() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 10)!
        XCTAssertEqual(pt.x, 100, accuracy: 0.01)
        XCTAssertEqual(pt.y, 200, accuracy: 0.01)
    }

    func testInterpolateLocationMidpoint() {
        let locs = [LocationPoint(t: 0, x: 0, y: 0), LocationPoint(t: 10, x: 100, y: 200)]
        let pt = RaceEngine.interpolateLocation(in: locs, at: 5)!
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
        var locs: [LocationPoint] = []
        for i in 0...20 {
            locs.append(LocationPoint(t: Float(i * 10), x: 100, y: 100))
        }
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

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

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

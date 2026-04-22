import Foundation

protocol BundleProviding {
    func url(forResource name: String?, withExtension ext: String?, subdirectory subpath: String?) -> URL?
}

extension Bundle: BundleProviding {}

final class RaceEngine {
    private(set) var raceTimeSeconds: Float = 0
    private(set) var currentRace: RaceData?
    private var raceCache: [Int: RaceData] = [:]
    var bundle: BundleProviding = Bundle.module
    private let decoder = JSONDecoder()

    var cacheSize: Int { raceCache.count }

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
        let secondsIntoHour = Int(Date().timeIntervalSince1970) % 3600
        raceTimeSeconds = Float(secondsIntoHour)
    }

    func setRace(_ race: RaceData) {
        currentRace = race
        resetTime()
    }

    // MARK: - Position interpolation (binary search)

    static func interpolateLocation(in locations: [LocationPoint], at t: Float) -> (x: Float, y: Float)? {
        guard !locations.isEmpty else { return nil }
        if t <= locations[0].t { return (locations[0].x, locations[0].y) }
        if t >= locations.last!.t { return (locations.last!.x, locations.last!.y) }

        var lo = 0
        var hi = locations.count - 1
        while lo < hi - 1 {
            let mid = (lo + hi) / 2
            if locations[mid].t <= t { lo = mid } else { hi = mid }
        }
        let a = locations[lo]
        let b = locations[hi]
        let frac = (t - a.t) / (b.t - a.t)
        return (a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
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

    // MARK: - Data loading

    func loadIndex() throws -> [String] {
        guard let url = bundle.url(forResource: "index", withExtension: "json", subdirectory: "Races") else {
            throw RaceError.resourceNotFound("index.json")
        }
        let data = try Data(contentsOf: url)
        let wrapper = try decoder.decode(IndexWrapper.self, from: data)
        return wrapper.races
    }

    func loadRace(index: Int) throws -> RaceData {
        if let cached = raceCache[index] { return cached }
        let races = try loadIndex()
        guard index >= 0 && index < races.count else {
            throw RaceError.invalidIndex(index)
        }
        let filename = races[index]
        guard let url = bundle.url(forResource: filename, withExtension: "json", subdirectory: "Races") else {
            throw RaceError.resourceNotFound("\(filename).json")
        }
        let data = try Data(contentsOf: url)
        let race = try decoder.decode(RaceData.self, from: data)
        raceCache[index] = race
        return race
    }

    enum RaceError: LocalizedError {
        case resourceNotFound(String)
        case invalidIndex(Int)
        case decodeFailed(String)
        var errorDescription: String? {
            switch self {
            case .resourceNotFound(let f): return "Race resource not found: \(f)"
            case .invalidIndex(let i): return "Invalid race index: \(i)"
            case .decodeFailed(let m): return "JSON decode failed: \(m)"
            }
        }
    }

    func extractYear(from filename: String) -> String {
        let parts = filename.split(separator: "_")
        return parts.last.map(String.init) ?? ""
    }
}

private struct IndexWrapper: Codable {
    let races: [String]
}

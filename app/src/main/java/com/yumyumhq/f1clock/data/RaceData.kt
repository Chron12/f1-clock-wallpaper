package com.yumyumhq.f1clock.data

import com.google.gson.annotations.SerializedName

/**
 * Complete race data model matching the F1 Clock API response.
 * Endpoint: https://f1.yumyumhq.com/api/race
 */
data class RaceData(
    val title: String = "",
    val circuitName: String = "",
    val circuitCoords: CircuitCoords? = null,
    val raceDate: String? = null,
    val totalLaps: Int = 0,
    val raceDurationS: Float = 3300f,
    val drivers: Map<String, Driver> = emptyMap(),
    val trackOutline: List<TrackPoint> = emptyList(),
    val trackSectors: TrackSectors? = null,
    val locations: Map<String, List<LocationPoint>> = emptyMap(),
    val positions: Map<String, List<PositionEntry>> = emptyMap(),
    val events: List<RaceEvent> = emptyList(),
    val laps: List<LapEntry> = emptyList(),
    val stints: Map<String, List<Stint>> = emptyMap(),
    val pitStops: Map<String, List<PitStop>> = emptyMap(),
    val fastestLap: FastestLap? = null
)

data class CircuitCoords(
    val lat: Double,
    val lon: Double
)

data class Driver(
    val code: String,
    val color: String,
    val name: String,
    val number: Int,
    val team: String
) {
    /** Parse hex color string to Android color int */
    fun colorInt(): Int {
        return try {
            android.graphics.Color.parseColor(color)
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }
    }
}

data class TrackPoint(
    val x: Float,
    val y: Float
)

data class TrackSectors(
    val sector1: List<TrackPoint> = emptyList(),
    val sector2: List<TrackPoint> = emptyList(),
    val sector3: List<TrackPoint> = emptyList()
)

data class LocationPoint(
    val t: Float,
    val x: Float,
    val y: Float
)

data class PositionEntry(
    val t: Float,
    val position: Int
)

data class RaceEvent(
    val category: String,
    val flag: String?,
    val message: String,
    val t: Float
)

data class LapEntry(
    val lap: Int,
    val t: Float
)

data class Stint(
    val compound: String,
    val lapStart: Int,
    val lapEnd: Int
)

data class PitStop(
    val t: Float
)

data class FastestLap(
    val driverNumber: Int,
    val duration: Float,
    val lap: Int,
    val t: Float
)

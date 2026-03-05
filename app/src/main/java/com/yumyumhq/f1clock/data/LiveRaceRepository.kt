package com.yumyumhq.f1clock.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Fetches live race data from the OpenF1 API when a race is in progress.
 * Falls back gracefully (returns null) on any network or parse error so the
 * caller can continue in HISTORICAL mode.
 */
class LiveRaceRepository(private val context: Context) {

    companion object {
        private const val TAG = "LiveRaceRepository"
        private const val BASE = OPENF1_BASE_URL

        // A race session is considered "live" if it started within the last 4 hours
        private const val LIVE_WINDOW_MS = 4 * 3600 * 1000L

        // Number of track-outline points subsampled from location data
        private const val TRACK_OUTLINE_POINTS = 50

        // Maximum location history entries fetched per driver
        private const val MAX_LOCATION_POINTS = 300
    }

    private val gson = Gson()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if a Race session appears to be active right now.
     * Checks /sessions for any Race session whose date_start is within the
     * last [LIVE_WINDOW_MS] milliseconds.
     */
    suspend fun isLiveRaceActive(): Boolean {
        val today = todayDateString()
        val url = "$BASE/sessions?session_type=Race&date_start>=$today"
        val json = fetchJson(url) ?: return false
        return try {
            val sessions = parseSessions(json)
            val nowMs = System.currentTimeMillis()
            sessions.any { session ->
                val startMs = parseIsoToMs(session.date_start)
                startMs != null && (nowMs - startMs) in 0..LIVE_WINDOW_MS
            }
        } catch (e: Exception) {
            Log.e(TAG, "isLiveRaceActive parse error", e)
            false
        }
    }

    /**
     * Fetches the current live race data from OpenF1 and converts it to the
     * app's [RaceData] format. Returns null if the session cannot be retrieved
     * or any critical fetch fails.
     */
    suspend fun getLiveRaceData(): RaceData? {
        return try {
            // 1. Session info
            val session = fetchLatestSession() ?: return null

            // 2. Drivers
            val openF1Drivers = fetchDrivers(session.session_key)
            if (openF1Drivers.isEmpty()) {
                Log.w(TAG, "No drivers found for session ${session.session_key}")
                return null
            }

            // 3. Latest position per driver
            val positionMap = fetchLatestPositions(session.session_key)

            // 4. Recent GPS locations (last MAX_LOCATION_POINTS per driver)
            val locationsByDriver = fetchLocations(session.session_key, openF1Drivers)

            // 5. Build RaceData
            buildRaceData(session, openF1Drivers, positionMap, locationsByDriver)
        } catch (e: Exception) {
            Log.e(TAG, "getLiveRaceData failed", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Fetch helpers
    // -------------------------------------------------------------------------

    private suspend fun fetchJson(url: String): String? = withContext(Dispatchers.IO) {
        try {
            URL(url).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: $url", e)
            null
        }
    }

    private suspend fun fetchLatestSession(): OpenF1Session? {
        val json = fetchJson("$BASE/sessions?session_key=latest") ?: return null
        return try {
            parseSessions(json).lastOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestSession parse error", e)
            null
        }
    }

    private suspend fun fetchDrivers(sessionKey: Int): List<OpenF1Driver> {
        val json = fetchJson("$BASE/drivers?session_key=$sessionKey") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<OpenF1Driver>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDrivers parse error", e)
            emptyList()
        }
    }

    /**
     * Returns a map of driver_number -> most recent position entry.
     */
    private suspend fun fetchLatestPositions(sessionKey: Int): Map<Int, OpenF1Position> {
        val json = fetchJson("$BASE/position?session_key=$sessionKey") ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<OpenF1Position>>() {}.type
            val all: List<OpenF1Position> = gson.fromJson(json, type)
            // Keep only the latest entry per driver (list is ordered oldest-first)
            all.groupBy { it.driver_number }
                .mapValues { (_, entries) -> entries.last() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestPositions parse error", e)
            emptyMap()
        }
    }

    /**
     * Fetches GPS location history for every driver and returns a map of
     * driver_number -> list of [OpenF1Location] (up to [MAX_LOCATION_POINTS]).
     * To avoid issuing one request per driver we fetch the entire session
     * location list at once and split client-side.
     */
    private suspend fun fetchLocations(
        sessionKey: Int,
        drivers: List<OpenF1Driver>
    ): Map<Int, List<OpenF1Location>> {
        val json = fetchJson("$BASE/location?session_key=$sessionKey") ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<OpenF1Location>>() {}.type
            val all: List<OpenF1Location> = gson.fromJson(json, type)
            val driverNumbers = drivers.map { it.driver_number }.toSet()
            all.filter { it.driver_number in driverNumbers }
                .groupBy { it.driver_number }
                .mapValues { (_, pts) -> pts.takeLast(MAX_LOCATION_POINTS) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLocations parse error", e)
            emptyMap()
        }
    }

    // -------------------------------------------------------------------------
    // Conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts OpenF1 API responses into the [RaceData] model used by the renderer.
     */
    private fun buildRaceData(
        session: OpenF1Session,
        openF1Drivers: List<OpenF1Driver>,
        positionMap: Map<Int, OpenF1Position>,
        locationsByDriver: Map<Int, List<OpenF1Location>>
    ): RaceData {

        // Build Driver map (keyed by driver number as string to match RaceData schema)
        val drivers: Map<String, Driver> = openF1Drivers.associate { d ->
            val color = d.team_colour?.let { c ->
                if (c.startsWith("#")) c else "#$c"
            } ?: "#FFFFFF"
            val pos = positionMap[d.driver_number]?.position ?: 0
            d.driver_number.toString() to Driver(
                code = d.name_acronym,
                color = color,
                name = d.broadcast_name,
                number = d.driver_number,
                team = d.team_name
            )
        }

        // Build location map: driver number string -> list of LocationPoint
        // OpenF1 x/y are in meters; use them directly (renderer normalises).
        // Time dimension (t) is synthesised as a 0..1 progress fraction.
        val locations: Map<String, List<LocationPoint>> = locationsByDriver
            .mapKeys { (k, _) -> k.toString() }
            .mapValues { (_, pts) ->
                val size = pts.size.toFloat().coerceAtLeast(1f)
                pts.mapIndexed { i, pt ->
                    LocationPoint(
                        t = i / size,
                        x = pt.x.toFloat(),
                        y = pt.y.toFloat()
                    )
                }
            }

        // Build positions map: driver number string -> single PositionEntry at t=1.0
        val positions: Map<String, List<PositionEntry>> = positionMap
            .mapKeys { (k, _) -> k.toString() }
            .mapValues { (_, pos) ->
                listOf(PositionEntry(t = 1f, position = pos.position))
            }

        // Derive track outline from all location data, subsampled to ~TRACK_OUTLINE_POINTS
        val allPoints: List<OpenF1Location> = locationsByDriver.values.flatten()
        val trackOutline: List<TrackPoint> = subsampleTrackOutline(allPoints, TRACK_OUTLINE_POINTS)

        return RaceData(
            title = "${session.country_name} Grand Prix",
            circuitName = session.circuit_short_name,
            raceDate = session.date_start.take(10), // "YYYY-MM-DD"
            totalLaps = 0, // Not available from /position endpoint
            raceDurationS = LIVE_WINDOW_MS.toFloat() / 1000f,
            drivers = drivers,
            trackOutline = trackOutline,
            locations = locations,
            positions = positions
        )
    }

    /**
     * Subsamples a combined list of location points to produce approximately
     * [targetCount] evenly-spaced track outline points.
     */
    private fun subsampleTrackOutline(
        allPoints: List<OpenF1Location>,
        targetCount: Int
    ): List<TrackPoint> {
        if (allPoints.isEmpty()) return emptyList()
        val step = (allPoints.size.toFloat() / targetCount).coerceAtLeast(1f)
        return (0 until targetCount).mapNotNull { i ->
            val idx = (i * step).toInt().coerceIn(0, allPoints.lastIndex)
            allPoints.getOrNull(idx)?.let { pt -> TrackPoint(x = pt.x.toFloat(), y = pt.y.toFloat()) }
        }.distinctBy { it.x to it.y }
    }

    // -------------------------------------------------------------------------
    // Date/time helpers
    // -------------------------------------------------------------------------

    /** Returns today's date as "YYYY-MM-DD". */
    private fun todayDateString(): String {
        return try {
            OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: Exception) {
            // Fallback: build string manually from system time
            val cal = java.util.Calendar.getInstance()
            "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }

    /**
     * Parses an ISO-8601 date/time string to epoch milliseconds.
     * Returns null if parsing fails.
     */
    private fun parseIsoToMs(dateStr: String): Long? {
        return try {
            OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
        } catch (e: Exception) {
            // Try appending Z if no timezone present
            try {
                OffsetDateTime.parse("${dateStr}Z").toInstant().toEpochMilli()
            } catch (e2: Exception) {
                Log.w(TAG, "Could not parse date: $dateStr")
                null
            }
        }
    }

    /** Parses a JSON array of [OpenF1Session] objects. */
    private fun parseSessions(json: String): List<OpenF1Session> {
        val type = object : TypeToken<List<OpenF1Session>>() {}.type
        return gson.fromJson(json, type)
    }
}

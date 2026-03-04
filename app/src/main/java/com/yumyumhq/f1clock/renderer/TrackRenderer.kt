package com.yumyumhq.f1clock.renderer

import android.graphics.*
import com.yumyumhq.f1clock.data.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders the F1 track, cars, leaderboard, and race info onto a Canvas.
 * Mirrors the web version's rendering logic for visual consistency.
 */
class TrackRenderer {

    // Transform from track coordinates to screen coordinates
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // Cached paints (avoid allocation in draw loop)
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#222222")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val carStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val startLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }

    // Sector colors matching web version
    private val sectorColors = intArrayOf(
        Color.parseColor("#4a3d5c"), // Sector 1 - muted purple
        Color.parseColor("#3d4a5c"), // Sector 2 - muted blue
        Color.parseColor("#3d5c4a")  // Sector 3 - muted green
    )

    // Tire compound colors
    private val tireColors = mapOf(
        "SOFT" to Color.parseColor("#ff3333"),
        "MEDIUM" to Color.parseColor("#ffdd00"),
        "HARD" to Color.WHITE,
        "INTERMEDIATE" to Color.parseColor("#44bb44"),
        "WET" to Color.parseColor("#4488ff")
    )

    // Reusable path for track drawing
    private val trackPath = Path()

    // Settings
    var showDriverLabels = true
    var showLeaderboard = true
    var showRaceInfo = true
    var showTireColors = false
    var showGlowEffects = true
    var showSectorColors = true
    var backgroundOpacity = 240
    var carSizeMultiplier = 1f  // 0.7 small, 1.0 medium, 1.4 large

    /**
     * Compute the coordinate transform to fit the track into the screen.
     */
    fun computeTransform(width: Int, height: Int, trackOutline: List<TrackPoint>) {
        if (trackOutline.isEmpty()) return

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (p in trackOutline) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }

        val rangeX = (maxX - minX).coerceAtLeast(1f)
        val rangeY = (maxY - minY).coerceAtLeast(1f)
        val pad = 40f
        val w = width - pad * 2
        val h = height - pad * 2
        val scale = min(w / rangeX, h / rangeY)

        scaleX = scale
        scaleY = scale
        offsetX = pad + (w - rangeX * scale) / 2 - minX * scale
        offsetY = pad + (h - rangeY * scale) / 2 + maxY * scale
    }

    /** Convert track coordinates to screen coordinates */
    private fun toScreenX(x: Float): Float = x * scaleX + offsetX
    private fun toScreenY(y: Float): Float = -y * scaleY + offsetY

    /**
     * Get a driver's interpolated position at time t.
     * Uses binary search + linear interpolation, matching the web version.
     */
    fun getDriverPos(locations: List<LocationPoint>, t: Float): PointF? {
        if (locations.isEmpty()) return null
        if (t <= locations[0].t) return PointF(locations[0].x, locations[0].y)
        if (t >= locations.last().t) return PointF(locations.last().x, locations.last().y)

        // Binary search for the bracketing interval
        var lo = 0
        var hi = locations.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) shr 1
            if (locations[mid].t <= t) lo = mid else hi = mid
        }

        val a = locations[lo]
        val b = locations[hi]
        val frac = (t - a.t) / (b.t - a.t)
        return PointF(
            a.x + (b.x - a.x) * frac,
            a.y + (b.y - a.y) * frac
        )
    }

    /**
     * Get driver's race position at time t.
     */
    fun getDriverRacePos(positions: List<PositionEntry>?, t: Float): Int {
        if (positions == null || positions.isEmpty()) return 99
        var best = positions[0]
        for (p in positions) {
            if (p.t <= t) best = p else break
        }
        return best.position
    }

    /**
     * Get current lap number at time t.
     */
    fun getCurrentLap(laps: List<LapEntry>, t: Float): Int {
        if (laps.isEmpty()) return 0
        var lap = 0
        for (l in laps) {
            if (l.t <= t) lap = l.lap else break
        }
        return lap
    }

    /**
     * Check if a driver has retired from the race at time t.
     */
    fun isDriverRetired(locations: List<LocationPoint>, t: Float, raceDurationS: Float): Boolean {
        if (locations.isEmpty()) return true
        val nearEnd = raceDurationS - 100
        val lastT = locations.last().t
        // If current time is 30+ seconds past their last data point, they've retired
        if (t > lastT + 30 && lastT < nearEnd) return true

        // Check if driver is stationary (stuck at same position for 60+ seconds)
        if (t > 60 && t < nearEnd) {
            val currentPos = getDriverPos(locations, t) ?: return true
            val pastPos = getDriverPos(locations, t - 60) ?: return true
            val dx = currentPos.x - pastPos.x
            val dy = currentPos.y - pastPos.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 50) return true
        }
        return false
    }

    /**
     * Get driver's current tire compound at a given lap.
     */
    fun getDriverTire(stints: List<Stint>?, lap: Int): String? {
        if (stints == null || stints.isEmpty()) return null
        for (stint in stints) {
            if (lap >= stint.lapStart && lap <= stint.lapEnd) return stint.compound
        }
        return stints.last().compound
    }

    /**
     * Main render method. Draws everything onto the provided canvas.
     */
    fun render(
        canvas: Canvas,
        raceData: RaceData,
        raceTimeSeconds: Float
    ) {
        val width = canvas.width
        val height = canvas.height
        val uiScale = min(width, height) / 700f

        // Background
        bgPaint.color = Color.argb(backgroundOpacity, 10, 10, 10)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw track
        drawTrack(canvas, raceData, uiScale)

        // Draw start/finish line
        drawStartFinishLine(canvas, raceData.trackOutline, uiScale)

        // Collect and draw cars
        val t = raceTimeSeconds
        val currentLap = getCurrentLap(raceData.laps, t)
        val driverStates = mutableListOf<DriverState>()

        for ((driverNum, driver) in raceData.drivers) {
            val locs = raceData.locations[driverNum] ?: continue
            val pos = getDriverPos(locs, t) ?: continue
            val racePos = getDriverRacePos(raceData.positions[driverNum], t)
            val retired = isDriverRetired(locs, t, raceData.raceDurationS)
            val tire = getDriverTire(raceData.stints[driverNum], currentLap)
            driverStates.add(DriverState(driverNum, driver, pos, racePos, retired, tire))
        }

        // Sort: draw back of field first (higher position number = drawn first, behind leaders)
        driverStates.sortByDescending { it.racePosition }

        // Check fastest lap flash
        val flDriverNum = raceData.fastestLap?.driverNumber?.toString()
        val flTime = raceData.fastestLap?.t ?: -1f
        val flFlashActive = flTime > 0 && t >= flTime && t < flTime + 3

        // Draw each active car
        for (ds in driverStates) {
            if (ds.retired) continue
            val sx = toScreenX(ds.position.x)
            val sy = toScreenY(ds.position.y)
            val baseRadius = 5f * uiScale * carSizeMultiplier

            // Glow effects
            if (showGlowEffects) {
                val isFlash = ds.driverNum == flDriverNum && flFlashActive
                if (isFlash) {
                    val pulse = 0.5f + 0.5f * kotlin.math.sin(t * 10.0).toFloat()
                    glowPaint.color = Color.argb(
                        ((0.6f + 0.4f * pulse) * 255).toInt(),
                        180, 77, 255
                    )
                    canvas.drawCircle(sx, sy, baseRadius + 8f, glowPaint)
                } else if (ds.racePosition <= 3) {
                    glowPaint.color = Color.argb(100, 
                        Color.red(ds.driver.colorInt()),
                        Color.green(ds.driver.colorInt()),
                        Color.blue(ds.driver.colorInt())
                    )
                    canvas.drawCircle(sx, sy, baseRadius + 6f, glowPaint)
                }
            }

            // Car dot
            val dotColor = if (showTireColors && ds.tire != null) {
                tireColors[ds.tire] ?: ds.driver.colorInt()
            } else {
                ds.driver.colorInt()
            }
            carPaint.color = dotColor
            canvas.drawCircle(sx, sy, baseRadius, carPaint)

            // Car outline
            carStrokePaint.strokeWidth = 1f * uiScale
            canvas.drawCircle(sx, sy, baseRadius, carStrokePaint)

            // Label top 3
            if (showDriverLabels && ds.racePosition <= 3) {
                textPaint.color = ds.driver.colorInt()
                textPaint.textSize = 7f * uiScale
                canvas.drawText(ds.driver.code, sx + 8f * uiScale, sy - 4f * uiScale, textPaint)
            }
        }

        // Draw leaderboard overlay
        if (showLeaderboard) {
            drawLeaderboard(canvas, driverStates, raceData, t, currentLap, uiScale)
        }

        // Draw race info overlay
        if (showRaceInfo) {
            drawRaceInfo(canvas, raceData, t, currentLap, uiScale)
        }
    }

    /**
     * Draw the track outline with sector coloring.
     */
    private fun drawTrack(canvas: Canvas, raceData: RaceData, uiScale: Float) {
        val sectors = raceData.trackSectors

        if (showSectorColors && sectors != null &&
            sectors.sector1.size > 1 && sectors.sector2.size > 1 && sectors.sector3.size > 1
        ) {
            // Draw each sector with its color
            val sectorData = listOf(sectors.sector1, sectors.sector2, sectors.sector3)
            for (i in sectorData.indices) {
                val points = sectorData[i]
                if (points.size < 2) continue

                trackPath.reset()
                trackPath.moveTo(toScreenX(points[0].x), toScreenY(points[0].y))
                for (j in 1 until points.size) {
                    trackPath.lineTo(toScreenX(points[j].x), toScreenY(points[j].y))
                }

                // Dark border
                trackBorderPaint.strokeWidth = 8f * uiScale
                canvas.drawPath(trackPath, trackBorderPaint)

                // Sector color
                trackPaint.color = sectorColors[i]
                trackPaint.strokeWidth = 4f * uiScale
                canvas.drawPath(trackPath, trackPaint)
            }
        } else {
            // Fallback: single-color track
            val outline = raceData.trackOutline
            if (outline.size < 2) return

            trackPath.reset()
            trackPath.moveTo(toScreenX(outline[0].x), toScreenY(outline[0].y))
            for (i in 1 until outline.size) {
                trackPath.lineTo(toScreenX(outline[i].x), toScreenY(outline[i].y))
            }
            trackPath.close()

            trackBorderPaint.strokeWidth = 6f * uiScale
            canvas.drawPath(trackPath, trackBorderPaint)

            trackPaint.color = Color.parseColor("#555555")
            trackPaint.strokeWidth = 2f * uiScale
            canvas.drawPath(trackPath, trackPaint)
        }
    }

    /**
     * Draw checkered start/finish line.
     */
    private fun drawStartFinishLine(canvas: Canvas, outline: List<TrackPoint>, uiScale: Float) {
        if (outline.size < 2) return

        val sx0 = toScreenX(outline[0].x)
        val sy0 = toScreenY(outline[0].y)
        val sx1 = toScreenX(outline[1].x)
        val sy1 = toScreenY(outline[1].y)

        val dx = sx1 - sx0
        val dy = sy1 - sy0
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val perpX = -dy / len
        val perpY = dx / len
        val lineLen = 12f * uiScale

        startLinePaint.strokeWidth = 3f * uiScale
        canvas.drawLine(
            sx0 - perpX * lineLen, sy0 - perpY * lineLen,
            sx0 + perpX * lineLen, sy0 + perpY * lineLen,
            startLinePaint
        )
    }

    /**
     * Draw the leaderboard overlay on the right side.
     */
    private fun drawLeaderboard(
        canvas: Canvas,
        driverStates: List<DriverState>,
        raceData: RaceData,
        t: Float,
        currentLap: Int,
        uiScale: Float
    ) {
        val active = driverStates.filter { !it.retired }.sortedBy { it.racePosition }
        val retired = driverStates.filter { it.retired }.sortedBy { it.racePosition }

        val fontSize = 9f * uiScale
        val lineHeight = 14f * uiScale
        val startX = canvas.width - 80f * uiScale
        val startY = 20f * uiScale
        val dotRadius = 3f * uiScale

        // Semi-transparent background for readability
        val bgRect = RectF(
            startX - 8f * uiScale,
            startY - 4f * uiScale,
            canvas.width.toFloat(),
            startY + (active.size + retired.size) * lineHeight + 4f * uiScale
        )
        bgPaint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRect(bgRect, bgPaint)

        textPaint.textSize = fontSize

        var y = startY + fontSize
        for ((idx, ds) in active.withIndex()) {
            val displayPos = idx + 1
            // Position number
            textPaint.color = Color.parseColor("#888888")
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$displayPos", startX + 8f * uiScale, y, textPaint)

            // Team color dot
            carPaint.color = ds.driver.colorInt()
            canvas.drawCircle(startX + 16f * uiScale, y - fontSize / 3, dotRadius, carPaint)

            // Driver code
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ds.driver.code, startX + 22f * uiScale, y, textPaint)

            // Tire indicator
            if (showTireColors && ds.tire != null) {
                val tireColor = tireColors[ds.tire]
                if (tireColor != null) {
                    carPaint.color = tireColor
                    canvas.drawCircle(startX + 52f * uiScale, y - fontSize / 3, 2f * uiScale, carPaint)
                }
            }

            // Fastest lap indicator
            if (raceData.fastestLap != null &&
                ds.driverNum == raceData.fastestLap.driverNumber.toString() &&
                raceData.fastestLap.t <= t
            ) {
                textPaint.color = Color.parseColor("#b44dff")
                textPaint.textSize = 6f * uiScale
                canvas.drawText("FL", startX + 58f * uiScale, y, textPaint)
                textPaint.textSize = fontSize
            }

            y += lineHeight
        }

        // Retired drivers
        for (ds in retired) {
            textPaint.color = Color.parseColor("#555555")
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("-", startX + 8f * uiScale, y, textPaint)

            carPaint.color = Color.argb(100,
                Color.red(ds.driver.colorInt()),
                Color.green(ds.driver.colorInt()),
                Color.blue(ds.driver.colorInt())
            )
            canvas.drawCircle(startX + 16f * uiScale, y - fontSize / 3, dotRadius, carPaint)

            textPaint.color = Color.parseColor("#555555")
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ds.driver.code, startX + 22f * uiScale, y, textPaint)

            textPaint.color = Color.parseColor("#ff4444")
            textPaint.textSize = 6f * uiScale
            canvas.drawText("DNF", startX + 52f * uiScale, y, textPaint)
            textPaint.textSize = fontSize

            y += lineHeight
        }
    }

    /**
     * Draw race title, lap counter, and clock.
     */
    private fun drawRaceInfo(
        canvas: Canvas,
        raceData: RaceData,
        t: Float,
        currentLap: Int,
        uiScale: Float
    ) {
        val cx = canvas.width / 2f

        // Race title
        centerTextPaint.color = Color.WHITE
        centerTextPaint.textSize = 10f * uiScale
        canvas.drawText(raceData.title, cx, 18f * uiScale, centerTextPaint)

        // Lap counter
        val lapText = "LAP $currentLap / ${raceData.totalLaps}"
        centerTextPaint.color = Color.parseColor("#aaaaaa")
        centerTextPaint.textSize = 8f * uiScale
        canvas.drawText(lapText, cx, canvas.height - 12f * uiScale, centerTextPaint)

        // Wall clock in bottom-left
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR)
        val h12 = if (h == 0) 12 else h
        val m = now.get(java.util.Calendar.MINUTE)
        val s = now.get(java.util.Calendar.SECOND)
        val ampm = if (now.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val clockText = String.format("%d:%02d:%02d %s", h12, m, s, ampm)

        textPaint.color = Color.parseColor("#666666")
        textPaint.textSize = 7f * uiScale
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(clockText, 8f * uiScale, canvas.height - 8f * uiScale, textPaint)

        // Progress bar at bottom
        val progressY = canvas.height - 3f * uiScale
        val progressH = 2f * uiScale
        val pct = (t / raceData.raceDurationS).coerceIn(0f, 1f)

        bgPaint.color = Color.parseColor("#222222")
        canvas.drawRect(0f, progressY, canvas.width.toFloat(), progressY + progressH, bgPaint)

        bgPaint.color = Color.parseColor("#e80020")
        canvas.drawRect(0f, progressY, canvas.width * pct, progressY + progressH, bgPaint)
    }

    /**
     * Internal state holder for a driver during rendering.
     */
    private data class DriverState(
        val driverNum: String,
        val driver: Driver,
        val position: PointF,
        val racePosition: Int,
        val retired: Boolean,
        val tire: String?
    )
}

package com.yumyumhq.f1clock.renderer

import android.graphics.*
import com.yumyumhq.f1clock.data.*
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the F1 track, cars, leaderboard, and race info onto a Canvas.
 * Premium visual design: prominent top-center clock, motion trails, circuit accent colors.
 */
class TrackRenderer {

    private var scaleX = 1f; private var scaleY = 1f
    private var offsetX = 0f; private var offsetY = 0f

    // Pre-allocated paints - never allocate in the render loop
    private val trackOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#111111") }
    private val trackPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackCenterPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(60, 255, 255, 255) }
    private val carPaint        = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val carStrokePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val trailPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.LEFT }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val shadowPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = Color.BLACK }
    private val bgPaint         = Paint().apply { style = Paint.Style.FILL }
    private val startLinePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val glowPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL) }
    private val accentPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(180, 0, 0, 0) }
    private val trackPath       = Path()
    private val pillRect        = RectF()

    private val sectorColors = intArrayOf(
        Color.parseColor("#6a2d9f"),   // Sector 1 - vivid purple
        Color.parseColor("#2d5a9f"),   // Sector 2 - vivid blue
        Color.parseColor("#2d9f5a"))   // Sector 3 - vivid green

    private val tireColors = mapOf("SOFT" to Color.parseColor("#ff3333"),
        "MEDIUM" to Color.parseColor("#ffdd00"), "HARD" to Color.WHITE,
        "INTERMEDIATE" to Color.parseColor("#44bb44"), "WET" to Color.parseColor("#4488ff"))

    // Circuit-specific accent colors keyed by partial circuit name
    private val circuitAccents = mapOf(
        "Bahrain" to 0xFFE8002D.toInt(),  "Saudi" to 0xFF00D2FF.toInt(),
        "Australia" to 0xFF00A550.toInt(), "Japan" to 0xFFFF0000.toInt(),
        "China" to 0xFFFF6B00.toInt(),    "Miami" to 0xFF00CED1.toInt(),
        "Monaco" to 0xFFFFD700.toInt(),   "Canada" to 0xFFFF0000.toInt(),
        "Spain" to 0xFFAA151B.toInt(),    "Austria" to 0xFFED2939.toInt(),
        "UK" to 0xFF003399.toInt(),       "Hungary" to 0xFF477050.toInt(),
        "Belgium" to 0xFFFFD700.toInt(),  "Netherlands" to 0xFFFF6600.toInt(),
        "Italy" to 0xFF009246.toInt(),    "Azerbaijan" to 0xFF0092BC.toInt(),
        "Singapore" to 0xFFEF3340.toInt(),"USA" to 0xFF3C3B6E.toInt(),
        "Mexico" to 0xFF006847.toInt(),   "Brazil" to 0xFF009C3B.toInt(),
        "Las Vegas" to 0xFFFFD700.toInt(),"Qatar" to 0xFF8D1B3D.toInt(),
        "Abu Dhabi" to 0xFF00A3D7.toInt())

    // Settings (public API)
    var showDriverLabels = true
    var showLeaderboard = true
    var showRaceInfo = true
    var showTireColors = false
    var showGlowEffects = true
    var showSectorColors = true
    var backgroundOpacity = 240
    var carSizeMultiplier = 1f

    fun computeTransform(width: Int, height: Int, trackOutline: List<TrackPoint>) {
        if (trackOutline.isEmpty()) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in trackOutline) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        val rangeX = (maxX - minX).coerceAtLeast(1f); val rangeY = (maxY - minY).coerceAtLeast(1f)
        val padTop = height * 0.18f; val padSide = width * 0.06f; val padBottom = height * 0.08f
        val w = width - padSide * 2; val h = height - padTop - padBottom
        val scale = min(w / rangeX, h / rangeY)
        scaleX = scale; scaleY = scale
        offsetX = padSide + (w - rangeX * scale) / 2 - minX * scale
        offsetY = padTop + (h - rangeY * scale) / 2 + maxY * scale
    }

    private fun toScreenX(x: Float) = x * scaleX + offsetX
    private fun toScreenY(y: Float) = -y * scaleY + offsetY

    fun getDriverPos(locations: List<LocationPoint>, t: Float): PointF? {
        if (locations.isEmpty()) return null
        if (t <= locations[0].t) return PointF(locations[0].x, locations[0].y)
        if (t >= locations.last().t) return PointF(locations.last().x, locations.last().y)
        var lo = 0; var hi = locations.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) shr 1; if (locations[mid].t <= t) lo = mid else hi = mid }
        val a = locations[lo]; val b = locations[hi]; val frac = (t - a.t) / (b.t - a.t)
        return PointF(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
    }

    fun getDriverRacePos(positions: List<PositionEntry>?, t: Float): Int {
        if (positions == null || positions.isEmpty()) return 99
        var best = positions[0]; for (p in positions) { if (p.t <= t) best = p else break }
        return best.position
    }

    fun getCurrentLap(laps: List<LapEntry>, t: Float): Int {
        if (laps.isEmpty()) return 0; var lap = 0
        for (l in laps) { if (l.t <= t) lap = l.lap else break }; return lap
    }

    fun isDriverRetired(locations: List<LocationPoint>, t: Float, raceDurationS: Float): Boolean {
        if (locations.isEmpty()) return true
        val nearEnd = raceDurationS - 100; val lastT = locations.last().t
        if (t > lastT + 30 && lastT < nearEnd) return true
        if (t > 60 && t < nearEnd) {
            val cur = getDriverPos(locations, t) ?: return true
            val past = getDriverPos(locations, t - 60) ?: return true
            val dx = cur.x - past.x; val dy = cur.y - past.y
            if (sqrt(dx * dx + dy * dy) < 50) return true
        }
        return false
    }

    fun getDriverTire(stints: List<Stint>?, lap: Int): String? {
        if (stints == null || stints.isEmpty()) return null
        for (stint in stints) { if (lap >= stint.lapStart && lap <= stint.lapEnd) return stint.compound }
        return stints.last().compound
    }

    /** Draw text with a black drop-shadow for readability over any wallpaper */
    private fun drawTextWithShadow(canvas: Canvas, text: String, x: Float, y: Float,
                                   paint: Paint, shadowOffset: Float = 2f) {
        shadowPaint.textSize = paint.textSize; shadowPaint.textAlign = paint.textAlign
        shadowPaint.typeface = paint.typeface; shadowPaint.alpha = 200
        canvas.drawText(text, x + shadowOffset, y + shadowOffset, shadowPaint)
        canvas.drawText(text, x, y, paint)
    }

    private fun circuitAccentColor(title: String): Int {
        for ((key, color) in circuitAccents) { if (title.contains(key, ignoreCase = true)) return color }
        return 0xFFE8002D.toInt()
    }

    fun render(canvas: Canvas, raceData: RaceData, raceTimeSeconds: Float) {
        val width = canvas.width; val height = canvas.height
        val uiScale = min(width, height) / 700f; val t = raceTimeSeconds
        val accentColor = circuitAccentColor(raceData.title)

        bgPaint.color = Color.argb(backgroundOpacity, 8, 8, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        drawTrack(canvas, raceData, uiScale, accentColor)
        drawStartFinishLine(canvas, raceData.trackOutline, uiScale)

        val currentLap = getCurrentLap(raceData.laps, t)
        val driverStates = mutableListOf<DriverState>()
        for ((driverNum, driver) in raceData.drivers) {
            val locs = raceData.locations[driverNum] ?: continue
            val pos = getDriverPos(locs, t) ?: continue
            val racePos = getDriverRacePos(raceData.positions[driverNum], t)
            val retired = isDriverRetired(locs, t, raceData.raceDurationS)
            val tire = getDriverTire(raceData.stints[driverNum], currentLap)
            driverStates.add(DriverState(driverNum, driver, pos, racePos, retired, tire, locs))
        }
        driverStates.sortByDescending { it.racePosition }

        val flDriverNum = raceData.fastestLap?.driverNumber?.toString()
        val flTime = raceData.fastestLap?.t ?: -1f
        val flFlash = flTime > 0 && t >= flTime && t < flTime + 3
        for (ds in driverStates) { if (!ds.retired) drawCar(canvas, ds, t, raceData, uiScale, flDriverNum, flFlash) }

        if (showLeaderboard) drawLeaderboard(canvas, driverStates, raceData, t, uiScale)
        if (showRaceInfo) drawRaceInfo(canvas, raceData, t, currentLap, uiScale, accentColor)
    }

    private fun drawTrack(canvas: Canvas, raceData: RaceData, uiScale: Float, accentColor: Int) {
        fun buildPath(points: List<TrackPoint>, close: Boolean = false) {
            trackPath.reset()
            trackPath.moveTo(toScreenX(points[0].x), toScreenY(points[0].y))
            for (i in 1 until points.size) trackPath.lineTo(toScreenX(points[i].x), toScreenY(points[i].y))
            if (close) trackPath.close()
        }
        fun strokeSector(color: Int) {
            trackOuterPaint.strokeWidth = 10f * uiScale; canvas.drawPath(trackPath, trackOuterPaint)
            trackPaint.color = color; trackPaint.strokeWidth = 5f * uiScale; canvas.drawPath(trackPath, trackPaint)
            trackCenterPaint.strokeWidth = 1.5f * uiScale; canvas.drawPath(trackPath, trackCenterPaint)
        }
        val sectors = raceData.trackSectors
        if (showSectorColors && sectors != null &&
            sectors.sector1.size > 1 && sectors.sector2.size > 1 && sectors.sector3.size > 1) {
            listOf(sectors.sector1, sectors.sector2, sectors.sector3).forEachIndexed { i, pts ->
                buildPath(pts); strokeSector(sectorColors[i])
            }
        } else {
            val outline = raceData.trackOutline; if (outline.size < 2) return
            buildPath(outline, close = true)
            val r = Color.red(accentColor) / 4 + 50; val g = Color.green(accentColor) / 4 + 50; val b = Color.blue(accentColor) / 4 + 50
            strokeSector(Color.argb(200, r, g, b))
        }
    }

    private fun drawStartFinishLine(canvas: Canvas, outline: List<TrackPoint>, uiScale: Float) {
        if (outline.size < 2) return
        val sx0 = toScreenX(outline[0].x); val sy0 = toScreenY(outline[0].y)
        val dx = toScreenX(outline[1].x) - sx0; val dy = toScreenY(outline[1].y) - sy0
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val px = -dy / len * 14f * uiScale; val py = dx / len * 14f * uiScale
        startLinePaint.strokeWidth = 3f * uiScale
        canvas.drawLine(sx0 - px, sy0 - py, sx0 + px, sy0 + py, startLinePaint)
    }

    private fun drawCar(canvas: Canvas, ds: DriverState, t: Float, raceData: RaceData,
                        uiScale: Float, flDriverNum: String?, flFlash: Boolean) {
        val sx = toScreenX(ds.position.x); val sy = toScreenY(ds.position.y)
        val r = 6f * uiScale * carSizeMultiplier
        val dotColor = if (showTireColors && ds.tire != null) tireColors[ds.tire] ?: ds.driver.colorInt() else ds.driver.colorInt()
        val cr = Color.red(dotColor); val cg = Color.green(dotColor); val cb = Color.blue(dotColor)

        // Motion trails: 4 ghost dots stepping back 0.4s each
        val offsets = floatArrayOf(1.6f, 1.2f, 0.8f, 0.4f)
        val alphas  = intArrayOf(50, 80, 110, 140)
        val radMul  = floatArrayOf(0.45f, 0.55f, 0.65f, 0.8f)
        for (i in offsets.indices) {
            val tp = getDriverPos(ds.locations, t - offsets[i]) ?: continue
            trailPaint.color = Color.argb(alphas[i], cr, cg, cb)
            canvas.drawCircle(toScreenX(tp.x), toScreenY(tp.y), r * radMul[i], trailPaint)
        }

        // Glow
        if (showGlowEffects) {
            when {
                ds.driverNum == flDriverNum && flFlash -> {
                    val pulse = 0.5f + 0.5f * sin(t * 10.0).toFloat()
                    glowPaint.color = Color.argb(((0.5f + 0.4f * pulse) * 255).toInt(), 180, 77, 255)
                    canvas.drawCircle(sx, sy, r + 10f, glowPaint)
                }
                ds.racePosition == 1 -> { glowPaint.color = Color.argb(120, 255, 215, 0); canvas.drawCircle(sx, sy, r + 8f, glowPaint) }
                ds.racePosition <= 3 -> { glowPaint.color = Color.argb(90, cr, cg, cb); canvas.drawCircle(sx, sy, r + 6f, glowPaint) }
            }
        }

        carPaint.color = dotColor; canvas.drawCircle(sx, sy, r, carPaint)
        carStrokePaint.strokeWidth = 1.2f * uiScale; canvas.drawCircle(sx, sy, r, carStrokePaint)

        // Always label top 3 with dark pill
        if (ds.racePosition <= 3) {
            val label = ds.driver.code; textPaint.textSize = 8f * uiScale
            val lx = sx + 9f * uiScale; val ly = sy - 5f * uiScale
            val tw = textPaint.measureText(label); val ph = 6f * uiScale; val pw = 3f * uiScale
            pillRect.set(lx - pw, ly - textPaint.textSize, lx + tw + pw, ly + ph * 0.5f)
            canvas.drawRoundRect(pillRect, 4f * uiScale, 4f * uiScale, pillPaint)
            textPaint.color = if (ds.racePosition == 1) Color.parseColor("#FFD700") else dotColor
            drawTextWithShadow(canvas, label, lx, ly, textPaint)
        }
    }

    private fun drawLeaderboard(canvas: Canvas, driverStates: List<DriverState>,
                                raceData: RaceData, t: Float, uiScale: Float) {
        val active  = driverStates.filter { !it.retired }.sortedBy { it.racePosition }
        val retired = driverStates.filter {  it.retired }.sortedBy { it.racePosition }
        val fs = 9f * uiScale; val lh = 13.5f * uiScale
        val lbX = 6f * uiScale; val lbY = canvas.height * 0.20f; val lbW = 80f * uiScale
        pillRect.set(lbX, lbY, lbX + lbW, lbY + (active.size + retired.size + 1.5f) * lh + 6f * uiScale)
        bgPaint.color = Color.argb(170, 0, 0, 0); canvas.drawRoundRect(pillRect, 6f * uiScale, 6f * uiScale, bgPaint)

        centerTextPaint.textSize = fs * 0.85f
        centerTextPaint.color = if ((t % 2) < 1) Color.parseColor("#FF0000") else Color.parseColor("#880000")
        canvas.drawText("LIVE", lbX + lbW / 2, lbY + lh, centerTextPaint)

        var y = lbY + lh * 2f; val dotR = 3f * uiScale
        for (ds in active) {
            textPaint.textSize = fs; textPaint.color = Color.parseColor("#999999"); textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${ds.racePosition}", lbX + 14f * uiScale, y, textPaint)
            carPaint.color = ds.driver.colorInt(); canvas.drawCircle(lbX + 20f * uiScale, y - fs / 3, dotR, carPaint)
            textPaint.color = Color.WHITE; textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ds.driver.code, lbX + 25f * uiScale, y, textPaint)
            if (raceData.fastestLap?.driverNumber?.toString() == ds.driverNum && (raceData.fastestLap?.t ?: 0f) <= t) {
                textPaint.color = Color.parseColor("#b44dff"); textPaint.textSize = fs * 0.75f
                canvas.drawText("FL", lbX + 60f * uiScale, y, textPaint); textPaint.textSize = fs
            }
            if (showTireColors && ds.tire != null) {
                val tc = tireColors[ds.tire]; if (tc != null) { carPaint.color = tc; canvas.drawCircle(lbX + 72f * uiScale, y - fs / 3, 2.5f * uiScale, carPaint) }
            }
            y += lh
        }
        for (ds in retired) {
            textPaint.textSize = fs * 0.85f; textPaint.color = Color.parseColor("#444444")
            textPaint.textAlign = Paint.Align.RIGHT; canvas.drawText("-", lbX + 14f * uiScale, y, textPaint)
            val dc = ds.driver.colorInt(); carPaint.color = Color.argb(80, Color.red(dc), Color.green(dc), Color.blue(dc))
            canvas.drawCircle(lbX + 20f * uiScale, y - fs / 3, dotR * 0.8f, carPaint)
            textPaint.textAlign = Paint.Align.LEFT; canvas.drawText(ds.driver.code, lbX + 25f * uiScale, y, textPaint)
            y += lh * 0.9f
        }
    }

    private fun drawRaceInfo(canvas: Canvas, raceData: RaceData, t: Float,
                             currentLap: Int, uiScale: Float, accentColor: Int) {
        val cx = canvas.width / 2f; val w = canvas.width.toFloat()

        // Prominent wall clock - top center
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
        val ampm = if (now.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val clockText = String.format("%d:%02d:%02d %s",
            h, now.get(java.util.Calendar.MINUTE), now.get(java.util.Calendar.SECOND), ampm)
        val clockSize = 18f * uiScale; val clockY = 28f * uiScale
        centerTextPaint.textSize = clockSize
        val cw = centerTextPaint.measureText(clockText); val pp = 10f * uiScale
        pillRect.set(cx - cw / 2 - pp, clockY - clockSize - 2f * uiScale, cx + cw / 2 + pp, clockY + 6f * uiScale)
        bgPaint.color = Color.argb(200, 0, 0, 0); canvas.drawRoundRect(pillRect, 10f * uiScale, 10f * uiScale, bgPaint)
        accentPaint.color = accentColor
        canvas.drawRect(pillRect.left + 8f * uiScale, pillRect.bottom - 2.5f * uiScale, pillRect.right - 8f * uiScale, pillRect.bottom, accentPaint)
        centerTextPaint.color = Color.WHITE; drawTextWithShadow(canvas, clockText, cx, clockY, centerTextPaint)

        // Race title below clock in circuit accent color
        val titleY = clockY + clockSize * 1.1f
        centerTextPaint.textSize = 9f * uiScale; centerTextPaint.color = accentColor
        drawTextWithShadow(canvas, raceData.title, cx, titleY, centerTextPaint)

        // Lap counter - bottom center with pill background
        if (raceData.totalLaps > 0) {
            val lapText = "LAP  $currentLap / ${raceData.totalLaps}"; centerTextPaint.textSize = 10f * uiScale
            centerTextPaint.color = Color.WHITE; val lapY = canvas.height - 14f * uiScale
            val lw = centerTextPaint.measureText(lapText)
            pillRect.set(cx - lw / 2 - 8f * uiScale, lapY - 12f * uiScale, cx + lw / 2 + 8f * uiScale, lapY + 4f * uiScale)
            bgPaint.color = Color.argb(170, 0, 0, 0); canvas.drawRoundRect(pillRect, 6f * uiScale, 6f * uiScale, bgPaint)
            drawTextWithShadow(canvas, lapText, cx, lapY, centerTextPaint)
        }

        // F1-red progress bar at very bottom
        val pct = (t / raceData.raceDurationS).coerceIn(0f, 1f); val barY = canvas.height - 4f * uiScale
        bgPaint.color = Color.parseColor("#1a1a1a"); canvas.drawRect(0f, barY, w, barY + 3f * uiScale, bgPaint)
        accentPaint.color = accentColor; canvas.drawRect(0f, barY, w * pct, barY + 3f * uiScale, accentPaint)
    }

    private data class DriverState(
        val driverNum: String, val driver: Driver, val position: PointF,
        val racePosition: Int, val retired: Boolean, val tire: String?,
        val locations: List<LocationPoint>
    )
}

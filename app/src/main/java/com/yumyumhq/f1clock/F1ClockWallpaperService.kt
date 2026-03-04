package com.yumyumhq.f1clock

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.yumyumhq.f1clock.data.RaceData
import com.yumyumhq.f1clock.data.RaceRepository
import com.yumyumhq.f1clock.renderer.RaceAnimator
import com.yumyumhq.f1clock.renderer.TrackRenderer
import kotlinx.coroutines.*

/**
 * F1 Clock Live Wallpaper Service.
 *
 * Displays real F1 race replays on the home screen, synced to the hour.
 * Each hour shows a different race from the 2019-2024 catalogue,
 * with cars moving around the track using real telemetry data.
 */
class F1ClockWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "F1ClockWallpaper"
    }

    override fun onCreateEngine(): Engine = F1ClockEngine()

    inner class F1ClockEngine : WallpaperService.Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val repository = RaceRepository()
        private val renderer = TrackRenderer()
        private val animator = RaceAnimator()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private var raceData: RaceData? = null
        private var visible = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        // Settings
        private var targetFps = 30
        private var reduceWhenInvisible = true
        private var dataRefreshIntervalMs = 3600000L
        private var lastDataFetchTime = 0L

        private val drawRunnable = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)

            // Load preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            loadPreferences(prefs)

            // Fetch initial race data
            fetchRaceData()
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunnable)
            scope.cancel()
            repository.shutdown()
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // Check if we need new data (hour changed)
                if (repository.shouldRefresh()) {
                    fetchRaceData()
                }
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height

            // Recompute transform for new dimensions
            raceData?.let {
                renderer.computeTransform(width, height, it.trackOutline)
            }

            if (visible) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            loadPreferences(prefs)
            // Recompute transform in case car size changed
            raceData?.let {
                renderer.computeTransform(surfaceWidth, surfaceHeight, it.trackOutline)
            }
        }

        private fun loadPreferences(prefs: SharedPreferences) {
            targetFps = prefs.getString("fps_target", "30")?.toIntOrNull() ?: 30
            renderer.showDriverLabels = prefs.getBoolean("show_driver_labels", true)
            renderer.showLeaderboard = prefs.getBoolean("show_leaderboard", true)
            renderer.showRaceInfo = prefs.getBoolean("show_race_info", true)
            renderer.showTireColors = prefs.getBoolean("show_tire_colors", false)
            renderer.showGlowEffects = prefs.getBoolean("glow_effects", true)
            renderer.showSectorColors = prefs.getBoolean("sector_colors", true)
            renderer.backgroundOpacity = prefs.getString("background_opacity", "240")?.toIntOrNull() ?: 240
            reduceWhenInvisible = prefs.getBoolean("reduce_when_invisible", true)
            dataRefreshIntervalMs = (prefs.getString("data_refresh_interval", "3600")?.toLongOrNull() ?: 3600L) * 1000L

            val carSize = prefs.getString("car_size", "medium") ?: "medium"
            renderer.carSizeMultiplier = when (carSize) {
                "small" -> 0.7f
                "large" -> 1.4f
                else -> 1.0f
            }
        }

        private fun fetchRaceData() {
            scope.launch {
                try {
                    val data = repository.getRaceData(forceRefresh = true)
                    if (data != null) {
                        raceData = data
                        renderer.computeTransform(surfaceWidth, surfaceHeight, data.trackOutline)
                        lastDataFetchTime = System.currentTimeMillis()
                        Log.i(TAG, "Race loaded: ${data.title}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching race data", e)
                }
            }
        }

        private fun drawFrame() {
            if (!visible && reduceWhenInvisible) return

            val data = raceData
            val holder = surfaceHolder

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null && data != null) {
                    val raceTime = animator.getRaceTimeSeconds()

                    // Check if hour changed → need new race data
                    if (animator.isNewHour(raceTime) && repository.shouldRefresh()) {
                        fetchRaceData()
                    }

                    // Check periodic refresh
                    if (System.currentTimeMillis() - lastDataFetchTime > dataRefreshIntervalMs) {
                        fetchRaceData()
                    }

                    renderer.render(canvas, data, raceTime)
                } else if (canvas != null) {
                    // No data yet — draw loading screen
                    canvas.drawColor(android.graphics.Color.parseColor("#0a0a0a"))
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.parseColor("#888888")
                        textSize = 14f
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    canvas.drawText(
                        "F1 CLOCK — LOADING...",
                        canvas.width / 2f,
                        canvas.height / 2f,
                        paint
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing frame", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error posting canvas", e)
                    }
                }
            }

            // Schedule next frame
            if (visible) {
                handler.removeCallbacks(drawRunnable)
                handler.postDelayed(drawRunnable, animator.getFrameDelayMs(targetFps))
            }
        }
    }
}

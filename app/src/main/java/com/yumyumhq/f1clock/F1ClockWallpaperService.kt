package com.yumyumhq.f1clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.yumyumhq.f1clock.data.RaceData
import com.yumyumhq.f1clock.data.LocalRaceRepository
import com.yumyumhq.f1clock.data.LiveRaceRepository
import com.yumyumhq.f1clock.data.RaceMode
import com.yumyumhq.f1clock.renderer.RaceAnimator
import com.yumyumhq.f1clock.renderer.TrackRenderer
import kotlinx.coroutines.*

/**
 * F1 Clock Live Wallpaper Service.
 *
 * Displays real F1 race replays on the home screen, synced to the hour.
 * Each hour shows a different race from the 2019-2024 catalogue,
 * with cars moving around the track using real telemetry data.
 *
 * Performance features:
 * - Software canvas via lockCanvas (hardware canvas causes flicker + breaks BlurMaskFilter)
 * - Adaptive FPS: full rate during active race, 10fps at start/end
 * - Battery-aware: caps at 20fps when battery < 20% and not charging
 * - Precise frame timing with SystemClock.uptimeMillis instead of postDelayed
 */
class F1ClockWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "F1ClockWallpaper"
    }

    override fun onCreateEngine(): Engine = F1ClockEngine()

    inner class F1ClockEngine : WallpaperService.Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val repository = LocalRaceRepository(applicationContext)
        private val liveRepository = LiveRaceRepository(applicationContext)
        private val renderer = TrackRenderer()
        private val animator = RaceAnimator()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private var raceData: RaceData? = null
        private var visible = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        // Settings
        private var targetFps = 60
        private var reduceWhenInvisible = true
        private var dataRefreshIntervalMs = 3600000L
        private var lastDataFetchTime = 0L
        private var raceMode = RaceMode.HISTORICAL
        private var spoilerFree = false
        private var spoilerDelayHours = 2

        // Adaptive FPS
        private var currentFps = 60
        private var adaptiveMode = true
        private var lastFrameTime = 0L

        // FPS monitoring
        private var frameCount = 0
        private var fpsTimer = 0L

        // Battery awareness
        private var batteryLow = false

        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                batteryLow = !isCharging && scale > 0 && (level * 100 / scale) < 20
            }
        }

        private val drawRunnable = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            // Load preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            loadPreferences(prefs)

            // Register battery receiver
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            applicationContext.registerReceiver(batteryReceiver, filter)

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
            try {
                applicationContext.unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Battery receiver already unregistered", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // Reset frame timing so we don't attempt to "catch up" after being hidden
                lastFrameTime = SystemClock.uptimeMillis()
                fpsTimer = System.currentTimeMillis()
                frameCount = 0

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
            targetFps = prefs.getString("fps_target", "60")?.toIntOrNull() ?: 60
            renderer.showDriverLabels = prefs.getBoolean("show_driver_labels", true)
            renderer.showLeaderboard = prefs.getBoolean("show_leaderboard", true)
            renderer.showRaceInfo = prefs.getBoolean("show_race_info", true)
            renderer.showTireColors = prefs.getBoolean("show_tire_colors", false)
            renderer.showGlowEffects = prefs.getBoolean("glow_effects", true)
            renderer.showSectorColors = prefs.getBoolean("sector_colors", true)
            renderer.backgroundOpacity = prefs.getString("background_opacity", "0")?.toIntOrNull() ?: 0
            reduceWhenInvisible = prefs.getBoolean("reduce_when_invisible", true)
            dataRefreshIntervalMs = (prefs.getString("data_refresh_interval", "3600")?.toLongOrNull() ?: 3600L) * 1000L

            raceMode = when (prefs.getString("race_mode", "historical")) {
                "live" -> RaceMode.LIVE
                "auto" -> RaceMode.AUTO
                else -> RaceMode.HISTORICAL
            }
            spoilerFree = prefs.getBoolean("spoiler_free", false)
            spoilerDelayHours = prefs.getString("spoiler_delay_hours", "2")?.toIntOrNull() ?: 2

            val carSize = prefs.getString("car_size", "medium") ?: "medium"
            renderer.carSizeMultiplier = when (carSize) {
                "small" -> 0.7f
                "large" -> 1.4f
                else -> 1.0f
            }
        }

        /**
         * Returns the effective FPS for this frame:
         * - Hard caps at 20fps when battery is low
         * - Drops to 10fps when race is in the static fringe (first/last 5%)
         * - Uses targetFps during active race playback
         */
        private fun effectiveFps(): Int {
            val fps = if (batteryLow) minOf(targetFps, 20) else targetFps

            // Adaptive: drop to 10fps at start/end of hour where nothing changes much
            if (adaptiveMode) {
                val raceTime = animator.getRaceTimeSeconds()
                val progress = animator.getRaceProgress(3600f)
                if (progress < 0.05f || progress > 0.95f) {
                    return minOf(fps, 10)
                }
            }

            return fps
        }

        /**
         * Schedule the next frame using precise uptimeMillis timing to avoid
         * drift accumulation from repeated postDelayed calls.
         */
        private fun scheduleNextFrame() {
            val now = SystemClock.uptimeMillis()
            val fps = effectiveFps().coerceIn(1, 60)
            val frameDelayMs = 1000L / fps
            val nextFrameTime = lastFrameTime + frameDelayMs
            val delay = maxOf(0L, nextFrameTime - now)
            lastFrameTime = now + delay
            handler.postAtTime(drawRunnable, lastFrameTime)
        }

        private fun fetchRaceData() {
            scope.launch {
                try {
                    val data = when (raceMode) {
                        RaceMode.LIVE -> {
                            liveRepository.getLiveRaceData() ?: repository.getRaceData(forceRefresh = true)
                        }
                        RaceMode.AUTO -> {
                            if (liveRepository.isLiveRaceActive()) {
                                liveRepository.getLiveRaceData() ?: repository.getRaceData(forceRefresh = true)
                            } else {
                                repository.getRaceData(forceRefresh = true)
                            }
                        }
                        RaceMode.HISTORICAL -> repository.getRaceData(forceRefresh = true)
                    }
                    if (data != null) {
                        raceData = data
                        renderer.computeTransform(surfaceWidth, surfaceHeight, data.trackOutline)
                        lastDataFetchTime = System.currentTimeMillis()
                        Log.i(TAG, "Race loaded [${raceMode}]: ${data.title}")
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

            // FPS monitoring (logs every 5 seconds)
            frameCount++
            val nowMs = System.currentTimeMillis()
            if (fpsTimer == 0L) fpsTimer = nowMs
            if (nowMs - fpsTimer >= 5000L) {
                val measuredFps = frameCount * 1000f / (nowMs - fpsTimer)
                Log.d(TAG, "FPS: ${"%.1f".format(measuredFps)} (target: ${effectiveFps()}, batteryLow: $batteryLow)")
                frameCount = 0
                fpsTimer = nowMs
            }

            var canvas: Canvas? = null
            try {
                // Always use software canvas — lockHardwareCanvas() causes double-buffer
                // flickering on WallpaperService surfaces and also breaks BlurMaskFilter.
                canvas = holder.lockCanvas()

                if (canvas != null && data != null) {
                    val raceTime = animator.getRaceTimeSeconds()

                    // Check if hour changed → need new race data
                    if (animator.isNewHour(raceTime) && repository.shouldRefresh()) {
                        fetchRaceData()
                    }

                    // Check periodic refresh — live/auto modes poll every 30s, historical every hour
                    val refreshInterval = if (raceMode != RaceMode.HISTORICAL) 30_000L else dataRefreshIntervalMs
                    if (System.currentTimeMillis() - lastDataFetchTime > refreshInterval) {
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

            // Schedule next frame with precise timing
            if (visible) {
                handler.removeCallbacks(drawRunnable)
                scheduleNextFrame()
            }
        }
    }
}

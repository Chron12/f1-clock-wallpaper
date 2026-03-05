package com.yumyumhq.f1clock.renderer

/**
 * Manages race time synchronization.
 * Uses the same time formula as the web version:
 *   raceTime = (System.currentTimeMillis() % 3600000) / 1000
 *
 * This means every hour starts a new "race replay" from t=0,
 * perfectly in sync with the web version.
 */
class RaceAnimator {

    private var lastRaceTime = -1f

    /**
     * Get the current race time in seconds.
     * Synchronized with the web version's clock.
     * Range: 0.0 to 3599.999 (one hour cycle)
     */
    fun getRaceTimeSeconds(): Float {
        return (System.currentTimeMillis() % 3600000L) / 1000f
    }

    /**
     * Returns the elapsed seconds since the last call to this method.
     * Capped at 0.1s to prevent large jumps after the wallpaper was hidden.
     * Returns 0 on the first call.
     */
    fun getDeltaTimeSeconds(): Float {
        val current = getRaceTimeSeconds()
        val delta = if (lastRaceTime < 0f) 0f else current - lastRaceTime
        lastRaceTime = current
        return delta.coerceIn(0f, 0.1f)
    }

    /**
     * Returns normalized race progress in [0.0, 1.0] for the full hour cycle.
     *
     * @param raceDurationS Total race duration in seconds (e.g. 3600f for full hour)
     */
    fun getRaceProgress(raceDurationS: Float): Float {
        return (getRaceTimeSeconds() / raceDurationS).coerceIn(0f, 1f)
    }

    /**
     * Get the frame delay in milliseconds for a target FPS.
     */
    fun getFrameDelayMs(targetFps: Int): Long {
        return (1000L / targetFps.coerceIn(1, 60))
    }

    /**
     * Check if the race has finished (past the duration).
     */
    fun isRaceFinished(raceTimeSeconds: Float, raceDurationS: Float, totalLaps: Int, currentLap: Int): Boolean {
        return currentLap >= totalLaps && raceTimeSeconds > raceDurationS + 10
    }

    /**
     * Check if we've looped back to the start of a new hour.
     */
    fun isNewHour(raceTimeSeconds: Float): Boolean {
        return raceTimeSeconds < 10
    }
}

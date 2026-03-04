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

    /**
     * Get the current race time in seconds.
     * Synchronized with the web version's clock.
     * Range: 0.0 to 3599.999 (one hour cycle)
     */
    fun getRaceTimeSeconds(): Float {
        return (System.currentTimeMillis() % 3600000L) / 1000f
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

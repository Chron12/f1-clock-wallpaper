package com.yumyumhq.f1clock.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Repository for fetching and caching race data.
 * Handles network requests, caching, and error recovery.
 */
class RaceRepository {

    companion object {
        private const val TAG = "RaceRepository"
        private const val BASE_URL = "https://f1.yumyumhq.com/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)    // Race data can be large
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val api: F1ClockApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(F1ClockApi::class.java)

    @Volatile
    private var cachedData: RaceData? = null
    private var lastFetchHour: Int = -1

    /**
     * Get race data, fetching from network if needed.
     * Caches per-hour since the race rotates hourly.
     */
    suspend fun getRaceData(forceRefresh: Boolean = false): RaceData? {
        val currentHour = ((System.currentTimeMillis() / 3600000) % 24).toInt()

        // Return cache if same hour and not forcing refresh
        if (!forceRefresh && cachedData != null && lastFetchHour == currentHour) {
            return cachedData
        }

        return withContext(Dispatchers.IO) {
            try {
                val data = api.getRace()
                cachedData = data
                lastFetchHour = currentHour
                Log.i(TAG, "Loaded race: ${data.title} (${data.drivers.size} drivers, ${data.totalLaps} laps)")
                data
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch race data: ${e.message}")
                // Return cached data as fallback
                cachedData
            }
        }
    }

    /**
     * Check if we should refresh (hour changed since last fetch).
     */
    fun shouldRefresh(): Boolean {
        val currentHour = ((System.currentTimeMillis() / 3600000) % 24).toInt()
        return lastFetchHour != currentHour
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

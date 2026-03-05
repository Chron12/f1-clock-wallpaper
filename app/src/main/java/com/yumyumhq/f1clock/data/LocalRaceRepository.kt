package com.yumyumhq.f1clock.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalRaceRepository(private val context: Context) {

    companion object {
        private const val TAG = "LocalRaceRepository"
        private const val TOTAL_RACES = 50
    }

    private val gson = Gson()

    @Volatile private var cachedData: RaceData? = null
    private var cachedRaceIndex: Int = -1

    fun currentRaceIndex(): Int {
        val hoursSinceEpoch = (System.currentTimeMillis() / 3600000L).toInt()
        return hoursSinceEpoch % TOTAL_RACES
    }

    fun shouldRefresh(): Boolean = cachedRaceIndex != currentRaceIndex()

    suspend fun getRaceData(forceRefresh: Boolean = false): RaceData? {
        val targetIndex = currentRaceIndex()
        if (!forceRefresh && cachedData != null && cachedRaceIndex == targetIndex) {
            return cachedData
        }
        return withContext(Dispatchers.IO) { loadRace(targetIndex) }
    }

    private fun loadRace(index: Int): RaceData? {
        return try {
            val indexJson = context.assets.open("races/index.json").bufferedReader().readText()
            val indexType = object : TypeToken<Map<String, List<String>>>() {}.type
            val raceList: List<String> = (gson.fromJson<Map<String, List<String>>>(indexJson, indexType))["races"]
                ?: return cachedData

            val raceName = raceList[index % raceList.size]
            val json = loadRaceJson(raceList, raceName) ?: return cachedData
            val data = gson.fromJson(json, RaceData::class.java)
            cachedData = data
            cachedRaceIndex = index
            Log.i(TAG, "Loaded race[$index]: ${data.title}")
            data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load race $index: ${e.message}")
            cachedData
        }
    }

    private fun loadRaceJson(raceList: List<String>, preferred: String): String? {
        return try {
            context.assets.open("races/$preferred.json").bufferedReader().readText()
        } catch (e: Exception) {
            // Fallback: try any available race
            raceList.firstNotNullOfOrNull { name ->
                try { context.assets.open("races/$name.json").bufferedReader().readText() }
                catch (e2: Exception) { null }
            }
        }
    }

    fun shutdown() { /* local only */ }
}

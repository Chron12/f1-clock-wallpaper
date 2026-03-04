package com.yumyumhq.f1clock.data

import retrofit2.http.GET

/**
 * Retrofit interface for the F1 Clock API.
 */
interface F1ClockApi {
    @GET("api/race")
    suspend fun getRace(): RaceData
}

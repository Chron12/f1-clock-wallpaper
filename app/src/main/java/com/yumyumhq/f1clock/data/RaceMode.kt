package com.yumyumhq.f1clock.data

enum class RaceMode {
    HISTORICAL,  // Cycling through saved races (default)
    LIVE,        // Real-time OpenF1 data during active race
    AUTO         // Auto-detect: live when race happening, historical otherwise
}

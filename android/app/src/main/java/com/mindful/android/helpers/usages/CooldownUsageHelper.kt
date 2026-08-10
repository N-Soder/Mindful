package com.mindful.android.helpers.usages

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * FORK: Usage lookups needed only by the cooldown gate.
 *
 * Kept separate from [ScreenUsageHelper] so the fork's diff against upstream stays
 * confined to new files wherever possible.
 */
object CooldownUsageHelper {
    /**
     * Default how far back to look for a previous session. Bounded deliberately: this
     * runs on the app-launch path, immediately before the overlay is shown, so a
     * multi-day event query would add visible latency to the gate.
     */
    private const val DEFAULT_LOOKBACK_MS = 48 * 60 * 60 * 1000L

    /**
     * Epoch millis of the last time [packageName] was *left* (paused or stopped), or -1
     * if it hasn't been within [lookbackMs].
     *
     * Deliberately keys off ACTIVITY_PAUSED/STOPPED rather than `UsageStats.lastTimeUsed`.
     * This is called just after the user opened the app, so `lastTimeUsed` would report
     * roughly "now" and the gate would always claim the app was last used seconds ago.
     * The most recent *pause* is the end of the previous real session.
     */
    fun fetchLastUsedMillis(
        usageStatsManager: UsageStatsManager,
        packageName: String,
        lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    ): Long {
        val end = System.currentTimeMillis()
        val start = end - lookbackMs
        var lastSeen = -1L

        runCatching {
            val usageEvents = usageStatsManager.queryEvents(start, end)
            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if (event.packageName != packageName) continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                        -> if (event.timeStamp > lastSeen) lastSeen = event.timeStamp

                    else -> {}
                }
            }
        }

        return lastSeen
    }
}

package com.mindful.android.helpers.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * FORK: Persistence for the cooldown gate.
 *
 * Two distinct things live here:
 *
 * 1. **Rolling 24h launch attempts.** [RestrictionManager]'s existing `appsLaunchCount`
 *    is an in-memory counter wiped at midnight, so it cannot answer "how many times in
 *    the last 24 hours" — at 00:05 it would always read zero. We therefore keep the
 *    actual attempt timestamps and prune anything older than 24h on read.
 *
 * 2. **Granted windows.** After the user chooses to continue into an app, it stays
 *    open-able until a deadline. Deliberately persisted so that a tracker service
 *    restart mid-window doesn't immediately re-gate the app the user just unlocked.
 */
object CooldownStore {
    private const val TAG = "Mindful.CooldownStore"
    private const val PREFS_NAME = "mindful_cooldown_prefs"
    private const val ATTEMPTS_KEY_PREFIX = "attempts_"
    private const val WINDOW_KEY_PREFIX = "window_until_"

    private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

    /** Guards against a pathological prefs entry growing without bound. */
    private const val MAX_STORED_ATTEMPTS = 500

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records an attempt to open [packageName] now, and returns the total number of
     * attempts within the trailing 24 hours *including* this one.
     */
    fun recordAttemptAndCount(context: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val timestamps = readAttempts(context, packageName, now).toMutableList()
        timestamps.add(now)

        // Keep the most recent entries if something has gone wrong and the list ballooned
        val trimmed =
            if (timestamps.size > MAX_STORED_ATTEMPTS) timestamps.takeLast(MAX_STORED_ATTEMPTS)
            else timestamps

        writeAttempts(context, packageName, trimmed)
        return trimmed.size
    }

    /** Number of attempts in the trailing 24 hours, without recording a new one. */
    fun attemptsInLast24h(context: Context, packageName: String): Int =
        readAttempts(context, packageName, System.currentTimeMillis()).size

    /**
     * Marks [packageName] as open-able for the next [windowSec] seconds.
     */
    fun grantWindow(context: Context, packageName: String, windowSec: Int) {
        val until = System.currentTimeMillis() + (windowSec * 1000L)
        prefs(context).edit().putLong("$WINDOW_KEY_PREFIX$packageName", until).apply()
        Log.d(TAG, "grantWindow: $packageName open-able for ${windowSec}s")
    }

    /** True while [packageName] is inside a granted window. */
    fun isWithinGrantedWindow(context: Context, packageName: String): Boolean {
        val until = prefs(context).getLong("$WINDOW_KEY_PREFIX$packageName", 0L)
        return until > System.currentTimeMillis()
    }

    /** Ends any granted window for [packageName], so the next launch gates again. */
    fun clearWindow(context: Context, packageName: String) {
        prefs(context).edit().remove("$WINDOW_KEY_PREFIX$packageName").apply()
    }

    /**
     * Reads attempt timestamps for [packageName], dropping anything that has aged out
     * of the 24h window relative to [now].
     */
    private fun readAttempts(context: Context, packageName: String, now: Long): List<Long> {
        val raw = prefs(context).getString("$ATTEMPTS_KEY_PREFIX$packageName", "") ?: ""
        if (raw.isEmpty()) return emptyList()

        val cutoff = now - TWENTY_FOUR_HOURS_MS
        return raw.split(',')
            .mapNotNull { it.toLongOrNull() }
            // Guard against clock changes putting timestamps in the future
            .filter { it in (cutoff + 1)..now }
    }

    private fun writeAttempts(context: Context, packageName: String, timestamps: List<Long>) {
        prefs(context).edit()
            .putString("$ATTEMPTS_KEY_PREFIX$packageName", timestamps.joinToString(","))
            .apply()
    }
}

package com.mindful.android.models

import android.util.Log
import com.mindful.android.enums.ReminderType
import org.json.JSONObject

/**
 * Represents app-level restriction configuration, such as usage time and access limits.
 */
data class AppRestriction(
    /**
     * Package name of the app.
     */
    val appPackage: String = "",

    /**
     * Daily timer limit for the app, in seconds.
     */
    val timerSec: Int = 0,

    /**
     * Launch limit per day.
     */
    val launchLimit: Int = 0,

    /**
     * Time of day (in minutes) from midnight when the app usage is allowed to start.
     */
    val activePeriodStart: Int = 0,

    /**
     * Time of day (in minutes) from midnight when the app usage is blocked.
     */
    val activePeriodEnd: Int = 0,

    /**
     * Type of usage reminders to show while using timed app.
     */
    val reminderType: ReminderType = ReminderType.NOTIFICATION,

    /**
     * ID of the restriction group this app belongs to (nullable).
     */
    val associatedGroupId: Int? = null,

    /**
     * FORK: Length of the breathing pause shown before this app opens, in seconds.
     * Zero disables the cooldown gate entirely.
     */
    val cooldownBreathSec: Int = 0,

    /**
     * FORK: How long the app stays open-able after the user chooses to continue,
     * in seconds. Stops app-switching from re-triggering the gate constantly.
     */
    val cooldownWindowSec: Int = 120,
) {
    companion object {
        /**
         * Constructs an [AppRestriction] from a JSON string.
         */
        fun fromJson(json: String): AppRestriction {
            val jsonObject = JSONObject(json)
            return AppRestriction(
                appPackage = jsonObject.optString("appPackage", ""),
                timerSec = jsonObject.optInt("timerSec", 0),
                launchLimit = jsonObject.optInt("launchLimit", 0),
                activePeriodStart = jsonObject.optInt("activePeriodStart", 0),
                activePeriodEnd = jsonObject.optInt("activePeriodEnd", 0),
                reminderType = ReminderType.fromName(jsonObject.optString("reminderType", "toast")),
                associatedGroupId = if (jsonObject.isNull("associatedGroupId")) null else jsonObject.optInt(
                    "associatedGroupId"
                ),
                cooldownBreathSec = jsonObject.optInt("cooldownBreathSec", 0),
                cooldownWindowSec = jsonObject.optInt("cooldownWindowSec", 120),
            )
        }
    }
}

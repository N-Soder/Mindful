package com.mindful.android.models

import com.mindful.android.enums.ReminderType
import com.mindful.android.enums.RestrictionType


data class RestrictionState(
    /** The type of restriction that this state represents **/
    val type: RestrictionType,

    /** The group name if this restriction belongs to a group **/
    val groupName: String? = null,

    /** Time left in milliseconds before the restriction starts **/
    val timeLeftMillis: Long = -1,

    /** Total screen time used so far in seconds **/
    val screenTimeUsed: Long = -1L,

    /** Screen time limit or timer in seconds **/
    val screenTimeLimit: Long = -1L,

    /** The type of reminder to show during app usage **/
    val reminderType: ReminderType = ReminderType.NONE,

    /** FORK: Length of the breathing pause for a [RestrictionType.COOLDOWN] state, in seconds **/
    val cooldownBreathSec: Int = 0,

    /** FORK: How many times this app was opened in the trailing 24 hours **/
    val launchAttempts24h: Int = -1,

    /** FORK: Epoch millis this app was last used, or -1 if never / unknown **/
    val lastUsedMillis: Long = -1L,
)
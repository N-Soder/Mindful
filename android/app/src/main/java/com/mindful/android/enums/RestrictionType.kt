package com.mindful.android.enums

enum class RestrictionType {
    FOCUS,
    BEDTIME,
    LAUNCH_COUNT,
    APP_TIMER,
    APP_ACTIVE_PERIOD,
    GROUP_TIMER,
    GROUP_ACTIVE_PERIOD,

    /**
     * FORK: A deliberate pause before the app opens. Unlike every other type here,
     * this is not a block — it shows a breathing screen followed by usage context,
     * then lets the user choose to continue or back out.
     */
    COOLDOWN,
}

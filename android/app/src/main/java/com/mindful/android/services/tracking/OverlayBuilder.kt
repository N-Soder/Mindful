package com.mindful.android.services.tracking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.MainThread
import com.mindful.android.R
import com.mindful.android.enums.RestrictionType
import com.mindful.android.models.RestrictionState
import com.mindful.android.utils.AppUtils
import com.mindful.android.utils.DateTimeUtils
import com.mindful.android.utils.MindfulQuotes
import com.mindful.android.utils.ThreadUtils

object OverlayBuilder {
    /** FORK: Scale the breathing circle starts (and returns to) on the exhale. */
    private const val EXHALE_SCALE = 0.72f

    /** FORK: Scale the breathing circle reaches at the peak of the inhale. */
    private const val INHALE_SCALE = 1.8f

    /** FORK: Circle opacity at rest and at the peak of the inhale. */
    private const val EXHALE_ALPHA = 0.22f
    private const val INHALE_ALPHA = 0.55f

    /** FORK: Duration of the breath → info cross-fade, in millis. */
    private const val CROSSFADE_MS = 450L

    /**
     * FORK: Delay before the breath starts, matching the window's own fade-in.
     *
     * Without it the circle's scale animation runs at the same time as the overlay
     * fading in, and the two competing animations are visibly rough on the first frames.
     */
    private const val BREATH_START_DELAY_MS = 260L

    @MainThread
    fun buildToastOverlay(
        context: Context,
        packageName: String,
        screenTimeUsedInMins: Int,
    ): View {
        // Inflate the custom layout for the dialog
        val inflater = LayoutInflater.from(context)
        val toastView = inflater.inflate(R.layout.overlay_toast_layout, null)

        // Resolve app icon and label
        val (appName, appIcon) = getAppLabelAndIcon(context, packageName)

        // App infos
        val appNameTxt = toastView.findViewById<TextView>(R.id.overlay_toast_app_name)
        val appIconImg = toastView.findViewById<ImageView>(R.id.overlay_toast_app_icon)
        appNameTxt.text = appName
        appIconImg.setImageDrawable(appIcon)

        // limit spent text
        val limitSpentTxt = toastView.findViewById<TextView>(R.id.overlay_toast_screen_time)
        limitSpentTxt.text = context.getString(
            R.string.app_screen_time_usage_info,
            DateTimeUtils.minutesToTimeStr(screenTimeUsedInMins)
        )

        // Set initial state
        toastView.alpha = 0f

        // Set click listener
        toastView.setOnClickListener {
            // Open mindful
            context.applicationContext.startActivity(
                AppUtils.getIntentForMindfulUri(
                    context,
                    "com.mindful.android://open/appDashboard?package=$packageName"
                )
            )
        }

        return toastView
    }

    /**
     * FORK: Builds the cooldown gate overlay.
     *
     * Runs in two phases inside a single view so the hand-off is a cross-fade rather than
     * a window teardown: a breathing pause, then usage context plus the choice to
     * continue or back out.
     *
     * @param onBackOut  User decided not to open the app.
     * @param onContinue User chose to go in anyway.
     */
    @MainThread
    fun buildCooldownOverlay(
        context: Context,
        packageName: String,
        state: RestrictionState,
        onBackOut: () -> Unit,
        onContinue: () -> Unit,
    ): View {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.overlay_cooldown_layout, null)

        val (appName, _) = getAppLabelAndIcon(context, packageName)

        // ---- Phase 2 content, populated up front so it's ready to fade in ----
        root.findViewById<TextView>(R.id.cooldown_attempts_count).text =
            state.launchAttempts24h.coerceAtLeast(1).toString()

        root.findViewById<TextView>(R.id.cooldown_attempts_label).text =
            context.getString(R.string.cooldown_attempts_label, appName)

        // Minutes on the app so far today
        val screenTimeTxt = root.findViewById<TextView>(R.id.cooldown_screen_time_today)
        if (state.screenTimeUsed > 0) {
            val usedMins = (state.screenTimeUsed / 60).toInt()
            screenTimeTxt.text = context.getString(
                R.string.cooldown_screen_time_today,
                DateTimeUtils.minutesToTimeStr(usedMins)
            )
        } else {
            screenTimeTxt.visibility = View.GONE
        }

        // How long since the previous session ended
        val lastUseTxt = root.findViewById<TextView>(R.id.cooldown_last_use)
        if (state.lastUsedMillis > 0) {
            val elapsedMins =
                ((System.currentTimeMillis() - state.lastUsedMillis) / 60_000L).toInt()
            lastUseTxt.text = context.getString(
                R.string.cooldown_last_use,
                DateTimeUtils.minutesToTimeStr(elapsedMins.coerceAtLeast(1))
            )
        } else {
            lastUseTxt.text = context.getString(R.string.cooldown_last_use_unknown)
        }

        // ---- Actions ----
        val backOutBtn = root.findViewById<Button>(R.id.cooldown_btn_dismiss)
        backOutBtn.text = context.getString(R.string.cooldown_btn_dismiss, appName)
        backOutBtn.setOnClickListener { ThreadUtils.runOnMainThread { onBackOut.invoke() } }

        val continueBtn = root.findViewById<Button>(R.id.cooldown_btn_continue)
        continueBtn.text = context.getString(R.string.cooldown_btn_continue, appName)
        continueBtn.setOnClickListener { ThreadUtils.runOnMainThread { onContinue.invoke() } }

        // ---- Phase 1: breathe, then reveal ----
        // Posted so the animation starts after the view has been laid out.
        root.post { animateBreathThenReveal(root, state.cooldownBreathSec) }

        return root
    }

    /**
     * FORK: One slow breath — expand on the inhale, contract on the exhale — spanning
     * [breathSec], then cross-fades the info panel in.
     *
     * A single cycle scaled to the configured duration, rather than a loop, so it reads
     * as a deliberate pause instead of a fidgeting animation.
     */
    private fun animateBreathThenReveal(root: View, breathSec: Int) {
        val breathPanel = root.findViewById<View>(R.id.cooldown_breath_panel)
        val circle = root.findViewById<View>(R.id.cooldown_breath_circle)
        val infoPanel = root.findViewById<View>(R.id.cooldown_info_panel)

        // Guard against a zero/negative configured duration reaching the animator
        val totalMs = (breathSec.coerceAtLeast(1)) * 1000L
        val halfMs = totalMs / 2

        // Promote to a hardware layer for the duration of the breath. Without this the
        // circle's background drawable is re-rasterized on every frame as it scales,
        // which is what makes a large slow scale look rough. On a hardware layer the
        // GPU just transforms a cached texture, so scale and alpha are near-free.
        circle.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Start small so the growth is pronounced rather than a subtle throb
        circle.scaleX = EXHALE_SCALE
        circle.scaleY = EXHALE_SCALE
        circle.alpha = EXHALE_ALPHA

        // Ease in and out at both ends — a linear scale reads as mechanical, and the
        // pause at each extreme is what makes it feel like breathing.
        val interpolator = AccelerateDecelerateInterpolator()

        // Inhale
        circle.animate()
            .scaleX(INHALE_SCALE).scaleY(INHALE_SCALE)
            .alpha(INHALE_ALPHA)
            .setInterpolator(interpolator)
            .setStartDelay(BREATH_START_DELAY_MS)
            .setDuration(halfMs)
            .withEndAction {
                // Exhale
                circle.animate()
                    .scaleX(EXHALE_SCALE).scaleY(EXHALE_SCALE)
                    .alpha(EXHALE_ALPHA)
                    .setInterpolator(interpolator)
                    .setStartDelay(0)
                    .setDuration(halfMs)
                    .withEndAction {
                        // Release the layer once there's nothing left to animate
                        circle.setLayerType(View.LAYER_TYPE_NONE, null)

                        // Cross-fade to the info phase
                        infoPanel.visibility = View.VISIBLE
                        infoPanel.animate().alpha(1f).setDuration(CROSSFADE_MS).start()
                        breathPanel.animate()
                            .alpha(0f)
                            .setDuration(CROSSFADE_MS)
                            .withEndAction { breathPanel.visibility = View.GONE }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    @MainThread
    fun buildFullScreenOverlay(
        context: Context,
        packageName: String,
        state: RestrictionState,
        dismissOverlay: () -> Unit,
        addReminderDelay: ((futureMinutes: Int) -> Unit)? = null,
    ): View {
        // Inflate the custom layout for the dialog
        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.overlay_full_screen_layout, null)

        // Set quote and author
        val quoteTxt = sheetView.findViewById<TextView>(R.id.overlay_sheet_quote)
        val quoteAuthorTxt = sheetView.findViewById<TextView>(R.id.overlay_sheet_quote_author)
        val randomQuote = MindfulQuotes.getRandomQuote()
        quoteTxt.text = buildString {
            append("\"")
            append(randomQuote.value)
            append("\"")
        }
        quoteAuthorTxt.text = buildString {
            append("— ")
            append(randomQuote.key)
        }

        // Resolve app icon and label
        val (appName, appIcon) = getAppLabelAndIcon(context, packageName)

        // App infos
        val appNameTxt = sheetView.findViewById<TextView>(R.id.overlay_sheet_app_name)
        val appIconImg = sheetView.findViewById<ImageView>(R.id.overlay_sheet_app_icon)
        appNameTxt.text = appName
        appIconImg.setImageDrawable(appIcon)

        // Emergency button (Visible only if limit is exhausted)
        if (state.screenTimeUsed >= state.screenTimeLimit) {
            val emergencyBtn = sheetView.findViewById<Button>(R.id.overlay_sheet_btn_emergency)
            emergencyBtn.visibility = View.VISIBLE
            emergencyBtn.setOnClickListener {
                ThreadUtils.runOnMainThread {
                    /// Open mindful
                    context.applicationContext.startActivity(
                        AppUtils.getIntentForMindfulUri(
                            context,
                            "com.mindful.android://open/appDashboard?package=$packageName"
                        )
                    )

                    /// Remove overlay
                    dismissOverlay.invoke()
                }
            }
        }

        // Limit type
        val limitType = sheetView.findViewById<TextView>(R.id.overlay_sheet_limit_type)
        limitType.text = context.getString(
            when (state.type) {
                RestrictionType.FOCUS -> R.string.app_paused_restriction_focus_mode
                RestrictionType.BEDTIME -> R.string.app_paused_restriction_bedtime_mode
                RestrictionType.LAUNCH_COUNT -> R.string.app_paused_restriction_launch_count
                RestrictionType.APP_TIMER -> R.string.app_paused_restriction_app_timer
                RestrictionType.APP_ACTIVE_PERIOD -> R.string.app_paused_restriction_app_active_period
                RestrictionType.GROUP_TIMER -> R.string.app_paused_restriction_group_timer
                RestrictionType.GROUP_ACTIVE_PERIOD -> R.string.app_paused_restriction_group_active_period
                // FORK: Cooldown states are rendered by buildCooldownOverlay, never here,
                // but the branch is required for exhaustiveness.
                RestrictionType.COOLDOWN -> R.string.app_paused_restriction_cooldown
            }
        )

        // Limit information
        val limitInfo = sheetView.findViewById<TextView>(R.id.overlay_sheet_limit_info)
        limitInfo.text = getRestrictionInfo(context, state)

        // Limit progress, and use more layout
        if (state.screenTimeLimit > 0 && state.screenTimeUsed > 0) {
            // Make limit parent container visible
            val limitContainer =
                sheetView.findViewById<LinearLayout>(R.id.overlay_sheet_limit_container)
            limitContainer.visibility = View.VISIBLE

            // calculate limit in minutes
            val usedLimitMins = (state.screenTimeUsed / 60).toInt()
            val totalLimitMins = (state.screenTimeLimit / 60).toInt()
            val leftLimitMins = if (usedLimitMins < totalLimitMins) totalLimitMins - usedLimitMins
            else 0

            // Progress bar
            val progressBar = sheetView.findViewById<ProgressBar>(R.id.overlay_sheet_limit_progress)
            progressBar.max = state.screenTimeLimit.toInt()
            progressBar.setProgress(state.screenTimeUsed.toInt(), true)

            // limit spent text
            val limitSpentTxt = sheetView.findViewById<TextView>(R.id.overlay_sheet_limit_spent)
            limitSpentTxt.text = DateTimeUtils.minutesToTimeStr(usedLimitMins)

            // limit left text
            val limitLeftTxt = sheetView.findViewById<TextView>(R.id.overlay_sheet_limit_left)
            limitLeftTxt.text = DateTimeUtils.minutesToTimeStr(leftLimitMins)

            // Wish to use more? options layout
            if (leftLimitMins > 0) {
                val useMoreOptions =
                    sheetView.findViewById<LinearLayout>(R.id.overlay_sheet_limit_options_use_more)
                useMoreOptions.visibility = View.VISIBLE

                // Iterate over reminders and the button ids map and set click listener
                // and make them visible if the left limit is more than the specified reminder time
                addReminderDelay?.let { callback ->
                    mapOf(
                        2 to R.id.overlay_sheet_reminder_option_btn_two_mins,
                        5 to R.id.overlay_sheet_reminder_option_btn_five_mins,
                        10 to R.id.overlay_sheet_reminder_option_btn_ten_mins,
                        20 to R.id.overlay_sheet_reminder_option_btn_twenty_mins
                    ).forEach { (reminder, btnId) ->
                        // Always show 2 minute option if left minutes > 0
                        if (reminder == 2 || leftLimitMins >= reminder) {
                            val button = sheetView.findViewById<Button>(btnId)
                            button.visibility = View.VISIBLE
                            button.setOnClickListener {
                                callback(reminder)
                                dismissOverlay.invoke()
                            }
                        }
                    }
                }
            }
        }

        // Close app button
        val closeAppBtn = sheetView.findViewById<Button>(R.id.overlay_sheet_btn_close_app)
        closeAppBtn.text = context.getString(R.string.app_paused_overlay_button_close_app, appName)
        closeAppBtn.setOnClickListener {
            ThreadUtils.runOnMainThread {
                /// Go to home
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.applicationContext.startActivity(homeIntent)

                /// Remove overlay
                dismissOverlay.invoke()
            }
        }

        return sheetView
    }


    fun getAppLabelAndIcon(
        context: Context,
        packageName: String,
    ): Pair<String, Drawable> {
        val packageManager = context.packageManager
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val appName = packageManager.getApplicationLabel(info).toString()
        val appIcon = packageManager.getApplicationIcon(info)

        return appName to appIcon
    }

    private fun getRestrictionInfo(
        context: Context,
        state: RestrictionState,
    ): String {
        val isLimitExhausted = state.screenTimeUsed >= state.screenTimeLimit

        return when (state.type) {
            RestrictionType.FOCUS ->
                context.getString(R.string.app_paused_reason_focus_session)

            RestrictionType.BEDTIME ->
                context.getString(R.string.app_paused_reason_bedtime)

            RestrictionType.LAUNCH_COUNT ->
                context.getString(R.string.app_paused_reason_launch_count_out)

            RestrictionType.APP_TIMER ->
                if (isLimitExhausted) context.getString(R.string.app_paused_reason_app_timer_out)
                else context.getString(R.string.app_paused_reason_app_timer_left)

            RestrictionType.APP_ACTIVE_PERIOD ->
                context.getString(R.string.app_paused_reason_app_active_period_over)

            RestrictionType.GROUP_TIMER ->
                if (isLimitExhausted) context.getString(
                    R.string.app_paused_reason_group_timer_out,
                    state.groupName
                )
                else context.getString(R.string.app_paused_reason_group_timer_left, state.groupName)

            RestrictionType.GROUP_ACTIVE_PERIOD ->
                context.getString(
                    R.string.app_paused_reason_group_active_period_over,
                    state.groupName
                )

            // FORK: Cooldown has its own overlay and writes its own copy, so this is
            // unreachable — present only to keep the `when` exhaustive.
            RestrictionType.COOLDOWN -> ""
        }
    }
}
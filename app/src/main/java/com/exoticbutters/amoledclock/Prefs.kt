package com.exoticbutters.amoledclock

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around the settings a long-press on the clock exposes.
 * Everything else (speed, connection distance, move interval, fade
 * timing) is a fixed constant shared with [ParticleClockView].
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("particle_clock_prefs", Context.MODE_PRIVATE)

    var clockSizeSp: Int
        get() = prefs.getInt(KEY_CLOCK_SIZE, DEFAULT_CLOCK_SIZE)
        set(value) = prefs.edit().putInt(KEY_CLOCK_SIZE, value).apply()

    var particleCount: Int
        get() = prefs.getInt(KEY_PARTICLE_COUNT, DEFAULT_PARTICLE_COUNT)
        set(value) = prefs.edit().putInt(KEY_PARTICLE_COUNT, value).apply()

    /** 0-100 chance that one particle in the field is the red accent particle. */
    var redChancePercent: Int
        get() = prefs.getInt(KEY_RED_CHANCE, DEFAULT_RED_CHANCE)
        set(value) = prefs.edit().putInt(KEY_RED_CHANCE, value).apply()

    /** 0-255 alpha used for the red particle and the lines it draws. */
    var redBrightness: Int
        get() = prefs.getInt(KEY_RED_BRIGHTNESS, DEFAULT_RED_BRIGHTNESS)
        set(value) = prefs.edit().putInt(KEY_RED_BRIGHTNESS, value).apply()

    /** Particle radius in tenths of a dp (e.g. 16 = 1.6dp), so the slider can use whole steps. */
    var particleSizeTenthsDp: Int
        get() = prefs.getInt(KEY_PARTICLE_SIZE, DEFAULT_PARTICLE_SIZE_TENTHS)
        set(value) = prefs.edit().putInt(KEY_PARTICLE_SIZE, value).apply()

    /** Whether the clock periodically fades to a new random spot on its own. */
    var autoMove: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MOVE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MOVE, value).apply()

    var moveIntervalSeconds: Int
        get() = prefs.getInt(KEY_MOVE_INTERVAL, DEFAULT_MOVE_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_MOVE_INTERVAL, value).apply()

    /** Manually-dragged clock position, as a fraction (0f-1f) of the screen. -1 means unset. */
    var manualPosX: Float
        get() = prefs.getFloat(KEY_MANUAL_X, -1f)
        set(value) = prefs.edit().putFloat(KEY_MANUAL_X, value).apply()

    var manualPosY: Float
        get() = prefs.getFloat(KEY_MANUAL_Y, -1f)
        set(value) = prefs.edit().putFloat(KEY_MANUAL_Y, value).apply()

    companion object {
        private const val KEY_CLOCK_SIZE = "clock_size_sp"
        private const val KEY_PARTICLE_COUNT = "particle_count"
        private const val KEY_RED_CHANCE = "red_chance_percent"
        private const val KEY_RED_BRIGHTNESS = "red_brightness"
        private const val KEY_PARTICLE_SIZE = "particle_size_tenths_dp"
        private const val KEY_AUTO_MOVE = "auto_move"
        private const val KEY_MOVE_INTERVAL = "move_interval_seconds"
        private const val KEY_MANUAL_X = "manual_pos_x"
        private const val KEY_MANUAL_Y = "manual_pos_y"

        const val CLOCK_SIZE_MIN = 28
        const val CLOCK_SIZE_MAX = 110
        const val DEFAULT_CLOCK_SIZE = 60

        const val PARTICLE_COUNT_MIN = 20
        const val PARTICLE_COUNT_MAX = 260
        const val DEFAULT_PARTICLE_COUNT = 120

        const val DEFAULT_RED_CHANCE = 30
        const val DEFAULT_RED_BRIGHTNESS = 200

        const val PARTICLE_SIZE_TENTHS_MIN = 5
        const val PARTICLE_SIZE_TENTHS_MAX = 40
        const val DEFAULT_PARTICLE_SIZE_TENTHS = 16

        const val MOVE_INTERVAL_MIN_SECONDS = 5
        const val MOVE_INTERVAL_MAX_SECONDS = 60
        const val DEFAULT_MOVE_INTERVAL_SECONDS = 12
    }
}

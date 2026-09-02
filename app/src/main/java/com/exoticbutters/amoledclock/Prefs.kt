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

    companion object {
        private const val KEY_CLOCK_SIZE = "clock_size_sp"
        private const val KEY_PARTICLE_COUNT = "particle_count"
        private const val KEY_RED_CHANCE = "red_chance_percent"
        private const val KEY_RED_BRIGHTNESS = "red_brightness"

        const val CLOCK_SIZE_MIN = 28
        const val CLOCK_SIZE_MAX = 110
        const val DEFAULT_CLOCK_SIZE = 60

        const val PARTICLE_COUNT_MIN = 20
        const val PARTICLE_COUNT_MAX = 260
        const val DEFAULT_PARTICLE_COUNT = 120

        const val DEFAULT_RED_CHANCE = 30
        const val DEFAULT_RED_BRIGHTNESS = 200
    }
}

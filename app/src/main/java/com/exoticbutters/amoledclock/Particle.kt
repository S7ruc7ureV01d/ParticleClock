package com.exoticbutters.amoledclock

import android.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A single drifting dot. Wraps around the edges of whatever bounds it is
 * currently told about, so it survives a resize (e.g. screen rotation)
 * without needing to be recreated.
 */
class Particle(
    var x: Float,
    var y: Float,
    private var vx: Float,
    private var vy: Float,
    var isRed: Boolean,
    /** This particle's own RGB (alpha ignored, applied separately when drawn). */
    var colorRgb: Int = Color.WHITE
) {
    fun update(width: Float, height: Float) {
        x += vx
        y += vy

        if (width <= 0f || height <= 0f) return

        if (x < 0f) x += width else if (x > width) x -= width
        if (y < 0f) y += height else if (y > height) y -= height

        // Keep particles sane after a large bounds change (e.g. rotation)
        // instead of drifting off into space forever.
        if (x < 0f || x > width) x = x.mod(width)
        if (y < 0f || y > height) y = y.mod(height)
    }

    companion object {
        fun random(width: Float, height: Float, speedMin: Float, speedMax: Float, isRed: Boolean, colorRgb: Int): Particle {
            val angle = Random.nextDouble(0.0, Math.PI * 2)
            val speed = Random.nextDouble(speedMin.toDouble(), speedMax.toDouble())
            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat()
            val x = Random.nextDouble(0.0, width.toDouble()).toFloat()
            val y = Random.nextDouble(0.0, height.toDouble()).toFloat()
            return Particle(x, y, vx, vy, isRed, colorRgb)
        }
    }
}

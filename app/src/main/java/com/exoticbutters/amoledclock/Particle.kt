package com.exoticbutters.amoledclock

import android.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A single drifting dot. It's allowed to wander past the edges of the
 * screen into an invisible border, bouncing back once it reaches the far
 * side of that border, rather than teleporting to the opposite edge. That
 * way a connection line to a particle that has drifted just off-screen
 * keeps drawing smoothly instead of snapping away.
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
    /** [minX]/[minY]/[maxX]/[maxY] describe the outer edge of the invisible border, not the screen itself. */
    fun update(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        x += vx
        y += vy

        if (maxX <= minX || maxY <= minY) return

        if (x < minX) {
            x = minX
            vx = -vx
        } else if (x > maxX) {
            x = maxX
            vx = -vx
        }

        if (y < minY) {
            y = minY
            vy = -vy
        } else if (y > maxY) {
            y = maxY
            vy = -vy
        }
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

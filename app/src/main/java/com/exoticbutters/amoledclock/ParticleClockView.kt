package com.exoticbutters.amoledclock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Renders the drifting particle field and the clock label on a plain
 * black canvas, and periodically fades the clock out to a brand new
 * random spot on screen so nothing burns into the panel.
 */
class ParticleClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val prefs = Prefs(context)
    private val density = resources.displayMetrics.density

    // ---- Fixed look & feel constants (not user-configurable) ----
    private val particleSpeedMinDp = 0.20f
    private val particleSpeedMaxDp = 0.80f
    private val particleRadiusDp = 1.6f
    private val connectionDistanceDp = 90f
    private val particleAlpha = 130
    private val edgePaddingDp = 20f

    private val moveIntervalMs = 12_000L
    private val fadeDurationMs = 320L
    private val clockTickMs = 1_000L
    private val frameIntervalMs = 33L

    private var particles: MutableList<Particle> = mutableListOf()

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var timeText = "00:00"

    private var viewWidth = 0f
    private var viewHeight = 0f

    private var clockCenterX = 0f
    private var clockCenterY = 0f
    private var clockAlpha = 255
    private var isAnimating = false
    private var hasPlacedInitially = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentFadeAnimator: ValueAnimator? = null

    private val particleTick = object : Runnable {
        override fun run() {
            for (p in particles) p.update(viewWidth, viewHeight)
            invalidate()
            mainHandler.postDelayed(this, frameIntervalMs)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            timeText = timeFormat.format(Calendar.getInstance().time)
            invalidate()
            mainHandler.postDelayed(this, clockTickMs)
        }
    }

    private val moveTick = object : Runnable {
        override fun run() {
            startMove()
            mainHandler.postDelayed(this, moveIntervalMs)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        loadTypeface()
        clockPaint.textSize = prefs.clockSizeSp * resources.displayMetrics.scaledDensity
    }

    private fun loadTypeface() {
        clockPaint.typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/dm_mono_light.ttf")
        } catch (e: Exception) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    /** Re-reads prefs and rebuilds whatever depends on them. Safe to call any time, live. */
    fun applySettings() {
        clockPaint.textSize = prefs.clockSizeSp * resources.displayMetrics.scaledDensity
        rebuildParticles()
        invalidate()
    }

    private fun rebuildParticles() {
        val count = prefs.particleCount
        val hasRed = Random.nextInt(100) < prefs.redChancePercent
        val redIndex = if (hasRed) Random.nextInt(count) else -1
        val w = if (viewWidth > 0f) viewWidth else 1080f
        val h = if (viewHeight > 0f) viewHeight else 1920f
        particles = MutableList(count) { i ->
            Particle.random(
                w, h,
                particleSpeedMinDp * density, particleSpeedMaxDp * density,
                isRed = i == redIndex
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        if (particles.isEmpty()) rebuildParticles()

        // A rotation (or first layout) can leave the clock outside the new
        // bounds, or mid-fade toward a spot that no longer makes sense.
        // Cancel any in-flight animation and snap into a valid, freshly
        // randomised spot immediately so it can never get stuck.
        currentFadeAnimator?.cancel()
        currentFadeAnimator = null
        isAnimating = false
        clockAlpha = 255

        if (!hasPlacedInitially) {
            hasPlacedInitially = true
            val p = randomClockCenter()
            clockCenterX = p[0]
            clockCenterY = p[1]
        } else {
            clockCenterX = clockCenterX.coerceIn(minCenterX(), maxCenterX())
            clockCenterY = clockCenterY.coerceIn(minCenterY(), maxCenterY())
        }
        invalidate()
    }

    // ---- Random placement, bounds-aware for any screen size/orientation ----

    private fun halfTextWidth(): Float = clockPaint.measureText(timeText) / 2f + edgePaddingDp * density
    private fun halfTextHeight(): Float {
        val fm = clockPaint.fontMetrics
        return (fm.descent - fm.ascent) / 2f + edgePaddingDp * density
    }

    private fun minCenterX(): Float = halfTextWidth().coerceAtMost(viewWidth / 2f)
    private fun maxCenterX(): Float = (viewWidth - halfTextWidth()).coerceAtLeast(viewWidth / 2f)
    private fun minCenterY(): Float = halfTextHeight().coerceAtMost(viewHeight / 2f)
    private fun maxCenterY(): Float = (viewHeight - halfTextHeight()).coerceAtLeast(viewHeight / 2f)

    private fun randomClockCenter(): FloatArray {
        val minX = minCenterX(); val maxX = maxCenterX()
        val minY = minCenterY(); val maxY = maxCenterY()
        val cx = if (maxX > minX) Random.nextFloat() * (maxX - minX) + minX else viewWidth / 2f
        val cy = if (maxY > minY) Random.nextFloat() * (maxY - minY) + minY else viewHeight / 2f
        return floatArrayOf(cx, cy)
    }

    // ---- Movement / fade animation ----

    private fun startMove() {
        if (isAnimating || viewWidth <= 0f || viewHeight <= 0f) return
        isAnimating = true

        val fadeOut = ValueAnimator.ofInt(255, 0).apply {
            duration = fadeDurationMs
            addUpdateListener {
                clockAlpha = it.animatedValue as Int
                invalidate()
            }
        }
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val p = randomClockCenter()
                clockCenterX = p[0]
                clockCenterY = p[1]

                val fadeIn = ValueAnimator.ofInt(0, 255).apply {
                    duration = fadeDurationMs
                    addUpdateListener {
                        clockAlpha = it.animatedValue as Int
                        invalidate()
                    }
                }
                fadeIn.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimating = false
                        currentFadeAnimator = null
                    }
                })
                currentFadeAnimator = fadeIn
                fadeIn.start()
            }
        })
        currentFadeAnimator = fadeOut
        fadeOut.start()
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        drawParticles(canvas)
        drawClock(canvas)
    }

    private fun drawParticles(canvas: Canvas) {
        val radius = particleRadiusDp * density
        val connectionDistance = connectionDistanceDp * density

        for (p in particles) {
            particlePaint.color = if (p.isRed) {
                Color.argb(prefs.redBrightness, 255, 50, 50)
            } else {
                Color.argb(particleAlpha, 255, 255, 255)
            }
            canvas.drawCircle(p.x, p.y, radius, particlePaint)
        }

        for (i in particles.indices) {
            val p1 = particles[i]
            for (j in i + 1 until particles.size) {
                val p2 = particles[j]
                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < connectionDistance) {
                    val fade = 1f - distance / connectionDistance
                    if (p1.isRed || p2.isRed) {
                        linePaint.color = Color.argb((prefs.redBrightness * fade).toInt(), 255, 50, 50)
                    } else {
                        linePaint.color = Color.argb((particleAlpha * fade).toInt(), 255, 255, 255)
                    }
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
                }
            }
        }
    }

    private fun drawClock(canvas: Canvas) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        clockPaint.alpha = clockAlpha
        val textWidth = clockPaint.measureText(timeText)
        val fm = clockPaint.fontMetrics
        val baselineY = clockCenterY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(timeText, clockCenterX - textWidth / 2f, baselineY, clockPaint)
    }

    // ---- Lifecycle ----

    fun start() {
        mainHandler.removeCallbacks(particleTick)
        mainHandler.removeCallbacks(clockTick)
        mainHandler.removeCallbacks(moveTick)
        timeText = timeFormat.format(Calendar.getInstance().time)
        mainHandler.post(particleTick)
        mainHandler.post(clockTick)
        mainHandler.postDelayed(moveTick, moveIntervalMs)
    }

    fun stop() {
        mainHandler.removeCallbacks(particleTick)
        mainHandler.removeCallbacks(clockTick)
        mainHandler.removeCallbacks(moveTick)
        currentFadeAnimator?.cancel()
    }
}

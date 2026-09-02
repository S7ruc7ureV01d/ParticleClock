package com.exoticbutters.amoledclock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
    private val connectionDistanceDp = 90f
    private val edgePaddingDp = 20f
    private val dragTouchMarginDp = 32f

    private val fadeDurationMs = 320L
    private val clockTickMs = 1_000L
    private val frameIntervalMs = 33L

    /** Called on any long-press that isn't consumed as a drag-to-reposition start. */
    var onRequestSettings: (() -> Unit)? = null

    private var particles: MutableList<Particle> = mutableListOf()

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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
    private var isDragging = false
    private var hasPlacedInitially = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentFadeAnimator: ValueAnimator? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            if (!prefs.autoMove && isNearClock(e.x, e.y)) {
                isDragging = true
                moveClockTo(e.x, e.y)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else {
                onRequestSettings?.invoke()
            }
        }
    })

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
            mainHandler.postDelayed(this, prefs.moveIntervalSeconds * 1000L)
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
        restartMoveScheduling()
        invalidate()
    }

    private fun restartMoveScheduling() {
        mainHandler.removeCallbacks(moveTick)
        currentFadeAnimator?.cancel()
        currentFadeAnimator = null
        isAnimating = false
        clockAlpha = 255

        if (viewWidth <= 0f || viewHeight <= 0f) return

        if (prefs.autoMove) {
            mainHandler.postDelayed(moveTick, prefs.moveIntervalSeconds * 1000L)
        } else {
            manualCenterOrNull()?.let {
                clockCenterX = it[0]
                clockCenterY = it[1]
            }
        }
        invalidate()
    }

    private fun rebuildParticles() {
        val count = prefs.particleCount
        val hasRed = Random.nextInt(100) < prefs.redChancePercent
        val redIndex = if (hasRed) Random.nextInt(count) else -1
        val w = if (viewWidth > 0f) viewWidth else 1080f
        val h = if (viewHeight > 0f) viewHeight else 1920f
        val speedScale = prefs.particleSpeedPercent / 100f
        val randomColors = prefs.randomParticleColors
        val baseColor = prefs.particleColor
        particles = MutableList(count) { i ->
            val isRed = i == redIndex
            val color = when {
                isRed -> Color.rgb(255, 50, 50)
                randomColors -> randomVividColor()
                else -> baseColor
            }
            Particle.random(
                w, h,
                particleSpeedMinDp * density * speedScale, particleSpeedMaxDp * density * speedScale,
                isRed = isRed,
                colorRgb = color
            )
        }
    }

    private fun randomVividColor(): Int {
        val hsv = floatArrayOf(Random.nextFloat() * 360f, 0.7f, 1f)
        return Color.HSVToColor(hsv)
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
            placeInitialClock()
        } else if (!prefs.autoMove) {
            val m = manualCenterOrNull()
            if (m != null) {
                clockCenterX = m[0]
                clockCenterY = m[1]
            } else {
                clockCenterX = clockCenterX.coerceIn(minCenterX(), maxCenterX())
                clockCenterY = clockCenterY.coerceIn(minCenterY(), maxCenterY())
            }
        } else {
            clockCenterX = clockCenterX.coerceIn(minCenterX(), maxCenterX())
            clockCenterY = clockCenterY.coerceIn(minCenterY(), maxCenterY())
        }
        invalidate()
    }

    // ---- Random / manual placement, bounds-aware for any screen size/orientation ----

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

    /** The saved drag-and-drop position, translated to this screen's current size, or null if never set. */
    private fun manualCenterOrNull(): FloatArray? {
        val fx = prefs.manualPosX
        val fy = prefs.manualPosY
        if (fx < 0f || fy < 0f) return null
        val cx = (fx * viewWidth).coerceIn(minCenterX(), maxCenterX())
        val cy = (fy * viewHeight).coerceIn(minCenterY(), maxCenterY())
        return floatArrayOf(cx, cy)
    }

    private fun placeInitialClock() {
        val center = if (!prefs.autoMove) {
            manualCenterOrNull() ?: floatArrayOf(viewWidth / 2f, viewHeight / 2f)
        } else {
            randomClockCenter()
        }
        clockCenterX = center[0]
        clockCenterY = center[1]
    }

    private fun isNearClock(x: Float, y: Float): Boolean {
        val margin = dragTouchMarginDp * density
        val halfW = clockPaint.measureText(timeText) / 2f + margin
        val halfH = (clockPaint.fontMetrics.descent - clockPaint.fontMetrics.ascent) / 2f + margin
        return x in (clockCenterX - halfW)..(clockCenterX + halfW) &&
            y in (clockCenterY - halfH)..(clockCenterY + halfH)
    }

    private fun moveClockTo(x: Float, y: Float) {
        currentFadeAnimator?.cancel()
        currentFadeAnimator = null
        isAnimating = false
        clockAlpha = 255
        clockCenterX = x.coerceIn(minCenterX(), maxCenterX())
        clockCenterY = y.coerceIn(minCenterY(), maxCenterY())
        invalidate()
    }

    private fun persistManualPosition() {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        prefs.manualPosX = clockCenterX / viewWidth
        prefs.manualPosY = clockCenterY / viewHeight
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (isDragging) {
                moveClockTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isDragging) {
                isDragging = false
                persistManualPosition()
                return true
            }
        }
        return true
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
        val radius = (prefs.particleSizeTenthsDp / 10f) * density
        val connectionDistance = connectionDistanceDp * density
        linePaint.strokeWidth = (prefs.lineWidthTenthsDp / 10f) * density
        val lineMode = prefs.lineColorMode
        val customLineColor = prefs.lineColor
        val particleBrightness = prefs.particleBrightness

        for (p in particles) {
            val alpha = if (p.isRed) prefs.redBrightness else particleBrightness
            particlePaint.color = Color.argb(alpha, Color.red(p.colorRgb), Color.green(p.colorRgb), Color.blue(p.colorRgb))
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
                    val baseAlpha = if (p1.isRed || p2.isRed) prefs.redBrightness else particleBrightness
                    val alpha = (baseAlpha * fade).toInt()

                    when (lineMode) {
                        Prefs.LINE_MODE_CUSTOM -> {
                            linePaint.shader = null
                            linePaint.color = Color.argb(alpha, Color.red(customLineColor), Color.green(customLineColor), Color.blue(customLineColor))
                        }
                        Prefs.LINE_MODE_GRADIENT -> {
                            val c1 = Color.argb(alpha, Color.red(p1.colorRgb), Color.green(p1.colorRgb), Color.blue(p1.colorRgb))
                            val c2 = Color.argb(alpha, Color.red(p2.colorRgb), Color.green(p2.colorRgb), Color.blue(p2.colorRgb))
                            linePaint.shader = LinearGradient(p1.x, p1.y, p2.x, p2.y, c1, c2, Shader.TileMode.CLAMP)
                        }
                        else -> {
                            linePaint.shader = null
                            if (p1.isRed || p2.isRed) {
                                linePaint.color = Color.argb(alpha, 255, 50, 50)
                            } else {
                                linePaint.color = Color.argb(alpha, 255, 255, 255)
                            }
                        }
                    }
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
                }
            }
        }
        linePaint.shader = null
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
        if (prefs.autoMove) {
            mainHandler.postDelayed(moveTick, prefs.moveIntervalSeconds * 1000L)
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(particleTick)
        mainHandler.removeCallbacks(clockTick)
        mainHandler.removeCallbacks(moveTick)
        currentFadeAnimator?.cancel()
    }
}

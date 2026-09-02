package com.fakegps.mocklocation.ui.tour

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

data class SpotlightStep(
    val targetViewProvider: () -> View?,
    val title: String,
    val description: String,
    val iconRes: Int,
    val stepNumber: Int,
    val totalSteps: Int,
    val isCircular: Boolean = false,
    val paddingDp: Float = 8f
)

class SpotlightTourOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Dark obsidian scrim background (88% opacity)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6040608")
        style = Paint.Style.FILL
    }

    // Aperture clear cutout
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // App Red theme hairline stroke
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE53935")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * context.resources.displayMetrics.density
    }

    // Glowing pulse ring in theme primary red
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DE53935")
        style = Paint.Style.STROKE
        strokeWidth = 4f * context.resources.displayMetrics.density
    }

    private var currentTargetRect = RectF()
    private var animatedTargetRect = RectF()
    private var animatedCornerRadius = 16f * context.resources.displayMetrics.density
    private var targetCornerRadius = 16f * context.resources.displayMetrics.density
    private var isCircular = false

    private var pulseScale = 1.0f
    private var pulseAlpha = 1.0f
    private var pulseAnimator: ValueAnimator? = null

    private var steps: List<SpotlightStep> = emptyList()
    private var currentStepIndex = 0
    private var onTourFinishedListener: (() -> Unit)? = null

    // UI elements from overlay card
    private val cardTooltip: MaterialCardView
    private val tvStepBadge: TextView
    private val ivStepIcon: ImageView
    private val tvStepTitle: TextView
    private val tvStepDescription: TextView
    private val pbSpotlightProgress: com.google.android.material.progressindicator.LinearProgressIndicator
    private val btnSkip: MaterialButton
    private val btnNext: MaterialButton

    private var rectAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        isFocusable = true

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_spotlight_tour_overlay, this, true)

        cardTooltip = view.findViewById(R.id.cardSpotlightTooltip)
        tvStepBadge = view.findViewById(R.id.tvSpotlightStepBadge)
        ivStepIcon = view.findViewById(R.id.ivSpotlightStepIcon)
        tvStepTitle = view.findViewById(R.id.tvSpotlightStepTitle)
        tvStepDescription = view.findViewById(R.id.tvSpotlightStepDescription)
        pbSpotlightProgress = view.findViewById(R.id.pbSpotlightProgress)
        btnSkip = view.findViewById(R.id.btnSpotlightSkip)
        btnNext = view.findViewById(R.id.btnSpotlightNext)

        btnSkip.setOnClickListener {
            finishTour()
        }

        btnNext.setOnClickListener {
            nextStep()
        }

        // Tapping the darkened scrim advances to next step
        setOnClickListener {
            nextStep()
        }

        cardTooltip.setOnClickListener {
            // Consume card clicks so they don't trigger scrim advance
        }

        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val frac = va.animatedValue as Float
                pulseScale = 1.0f + (frac * 0.04f)
                pulseAlpha = 0.4f + (frac * 0.6f)
                pulsePaint.alpha = (pulseAlpha * 90).toInt()
                invalidate()
            }
            start()
        }
    }

    fun setTourColors(accentColor: Int, pulseColor: Int) {
        strokePaint.color = accentColor
        pulsePaint.color = pulseColor
        cardTooltip.strokeColor = accentColor
        btnNext.backgroundTintList = ColorStateList.valueOf(accentColor)
        pbSpotlightProgress.setIndicatorColor(accentColor)
        invalidate()
    }

    fun startTour(tourSteps: List<SpotlightStep>, onFinished: (() -> Unit)? = null) {
        if (tourSteps.isEmpty()) {
            onFinished?.invoke()
            return
        }
        steps = tourSteps
        currentStepIndex = 0
        onTourFinishedListener = onFinished
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(280).start()
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index !in steps.indices) {
            finishTour()
            return
        }
        currentStepIndex = index
        val step = steps[index]

        tvStepBadge.text = "STEP ${step.stepNumber} OF ${step.totalSteps}"
        ivStepIcon.setImageResource(step.iconRes)
        tvStepTitle.text = step.title
        tvStepDescription.text = step.description
        pbSpotlightProgress.max = step.totalSteps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            pbSpotlightProgress.setProgress(step.stepNumber, true)
        } else {
            pbSpotlightProgress.progress = step.stepNumber
        }
        btnNext.text = if (index == steps.size - 1) "Got It" else "Next"
        btnSkip.visibility = if (index == steps.size - 1) View.GONE else View.VISIBLE

        val targetView = step.targetViewProvider.invoke()
        if (targetView != null && targetView.isAttachedToWindow && targetView.visibility == View.VISIBLE) {
            targetView.post {
                val loc = IntArray(2)
                targetView.getLocationOnScreen(loc)
                val overlayLoc = IntArray(2)
                getLocationOnScreen(overlayLoc)

                val density = resources.displayMetrics.density
                val pad = step.paddingDp * density
                val left = (loc[0] - overlayLoc[0]).toFloat() - pad
                val top = (loc[1] - overlayLoc[1]).toFloat() - pad
                val right = left + targetView.width + (pad * 2)
                val bottom = top + targetView.height + (pad * 2)

                val newRect = RectF(left, top, right, bottom)
                isCircular = step.isCircular
                targetCornerRadius = if (isCircular) (newRect.width() / 2f) else (16f * density)

                animateToRect(newRect)
                positionTooltip(newRect)
            }
        } else {
            // Fallback centered cutout if target is off-screen
            val cx = width / 2f
            val cy = height / 2f
            val density = resources.displayMetrics.density
            val newRect = RectF(cx - (80f * density), cy - (40f * density), cx + (80f * density), cy + (40f * density))
            targetCornerRadius = 16f * density
            animateToRect(newRect)
            positionTooltip(newRect)
        }
    }

    private fun animateToRect(target: RectF) {
        rectAnimator?.cancel()
        val startRect = RectF(if (animatedTargetRect.isEmpty) target else animatedTargetRect)
        val startRadius = animatedCornerRadius
        rectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                animatedTargetRect.left = startRect.left + (target.left - startRect.left) * fraction
                animatedTargetRect.top = startRect.top + (target.top - startRect.top) * fraction
                animatedTargetRect.right = startRect.right + (target.right - startRect.right) * fraction
                animatedTargetRect.bottom = startRect.bottom + (target.bottom - startRect.bottom) * fraction
                animatedCornerRadius = startRadius + (targetCornerRadius - startRadius) * fraction
                invalidate()
            }
            start()
        }
    }

    private fun positionTooltip(targetRect: RectF) {
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val margin = 16f * density

        cardTooltip.post {
            val cardHeight = cardTooltip.height.toFloat()
            // Place below if target is in the top 50% of the screen; otherwise place above
            val placeBelow = targetRect.centerY() < (screenHeight * 0.48f)

            val targetY = if (placeBelow) {
                targetRect.bottom + margin
            } else {
                targetRect.top - cardHeight - margin
            }

            val clampedY = targetY.coerceIn(
                margin + (36f * density),
                (screenHeight - cardHeight - margin - (36f * density))
            )

            cardTooltip.animate()
                .y(clampedY)
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun nextStep() {
        if (currentStepIndex < steps.size - 1) {
            showStep(currentStepIndex + 1)
        } else {
            finishTour()
        }
    }

    fun finishTour() {
        pulseAnimator?.cancel()
        animate().alpha(0f).setDuration(220).withEndAction {
            visibility = View.GONE
            onTourFinishedListener?.invoke()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (!animatedTargetRect.isEmpty) {
            if (isCircular) {
                val cx = animatedTargetRect.centerX()
                val cy = animatedTargetRect.centerY()
                val baseRadius = (animatedTargetRect.width() / 2f).coerceAtLeast(animatedTargetRect.height() / 2f)
                canvas.drawCircle(cx, cy, baseRadius, clearPaint)

                // Pulsing glow ring
                val pulsedRadius = baseRadius * pulseScale
                canvas.drawCircle(cx, cy, pulsedRadius, pulsePaint)
                canvas.drawCircle(cx, cy, baseRadius, strokePaint)
            } else {
                canvas.drawRoundRect(animatedTargetRect, animatedCornerRadius, animatedCornerRadius, clearPaint)

                // Pulsing outer rounded rect
                val inset = -((pulseScale - 1.0f) * 20f)
                val pulsedRect = RectF(
                    animatedTargetRect.left + inset,
                    animatedTargetRect.top + inset,
                    animatedTargetRect.right - inset,
                    animatedTargetRect.bottom - inset
                )
                canvas.drawRoundRect(pulsedRect, animatedCornerRadius + 2f, animatedCornerRadius + 2f, pulsePaint)
                canvas.drawRoundRect(animatedTargetRect, animatedCornerRadius, animatedCornerRadius, strokePaint)
            }
        }
    }
}

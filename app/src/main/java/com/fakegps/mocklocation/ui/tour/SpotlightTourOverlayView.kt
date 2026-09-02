package com.fakegps.mocklocation.ui.tour

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
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

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B305070A") // 70% Obsidian dark scrim
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Minimalist subtle Apple white hairline border
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    private var currentTargetRect = RectF()
    private var animatedTargetRect = RectF()
    private var isCircular = false
    private var cornerRadius = 16f * context.resources.displayMetrics.density

    private var steps: List<SpotlightStep> = emptyList()
    private var currentStepIndex = 0
    private var onTourFinishedListener: (() -> Unit)? = null

    // UI elements from overlay card
    private val cardTooltip: MaterialCardView
    private val tvStepBadge: TextView
    private val ivStepIcon: ImageView
    private val tvStepTitle: TextView
    private val tvStepDescription: TextView
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
        btnSkip = view.findViewById(R.id.btnSpotlightSkip)
        btnNext = view.findViewById(R.id.btnSpotlightNext)

        btnSkip.setOnClickListener {
            finishTour()
        }

        btnNext.setOnClickListener {
            nextStep()
        }

        // Tap outside card advances or focuses
        setOnClickListener {
            // Prevent accidental dismiss; advance next step
            nextStep()
        }
        cardTooltip.setOnClickListener {
            // Consume click on card so it doesn't trigger parent click
        }
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
        animate().alpha(1f).setDuration(250).start()
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index !in steps.indices) {
            finishTour()
            return
        }
        currentStepIndex = index
        val step = steps[index]

        tvStepBadge.text = "${step.stepNumber} OF ${step.totalSteps}"
        ivStepIcon.setImageResource(step.iconRes)
        tvStepTitle.text = step.title
        tvStepDescription.text = step.description
        btnNext.text = if (index == steps.size - 1) "Get Started" else "Next"
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
                cornerRadius = if (isCircular) (newRect.width() / 2f) else (14f * density)

                animateToRect(newRect)
                positionTooltip(newRect)
            }
        } else {
            // Fallback centered cutout if target is off-screen
            val cx = width / 2f
            val cy = height / 2f
            val newRect = RectF(cx - 100, cy - 100, cx + 100, cy + 100)
            animateToRect(newRect)
            positionTooltip(newRect)
        }
    }

    private fun animateToRect(target: RectF) {
        rectAnimator?.cancel()
        val startRect = RectF(if (animatedTargetRect.isEmpty) target else animatedTargetRect)
        rectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                animatedTargetRect.left = startRect.left + (target.left - startRect.left) * fraction
                animatedTargetRect.top = startRect.top + (target.top - startRect.top) * fraction
                animatedTargetRect.right = startRect.right + (target.right - startRect.right) * fraction
                animatedTargetRect.bottom = startRect.bottom + (target.bottom - startRect.bottom) * fraction
                invalidate()
            }
            start()
        }
    }

    private fun positionTooltip(targetRect: RectF) {
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val margin = 20f * density

        cardTooltip.post {
            val cardHeight = cardTooltip.height.toFloat()
            val placeBelow = targetRect.centerY() < (screenHeight * 0.45f)

            val targetY = if (placeBelow) {
                targetRect.bottom + margin
            } else {
                targetRect.top - cardHeight - margin
            }

            val clampedY = targetY.coerceIn(margin + (40f * density), (screenHeight - cardHeight - margin - (40f * density)))

            cardTooltip.animate()
                .y(clampedY)
                .alpha(1f)
                .setDuration(250)
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
        animate().alpha(0f).setDuration(200).withEndAction {
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
                val radius = (animatedTargetRect.width() / 2f).coerceAtLeast(animatedTargetRect.height() / 2f)
                canvas.drawCircle(cx, cy, radius, clearPaint)
                canvas.drawCircle(cx, cy, radius, strokePaint)
            } else {
                canvas.drawRoundRect(animatedTargetRect, cornerRadius, cornerRadius, clearPaint)
                canvas.drawRoundRect(animatedTargetRect, cornerRadius, cornerRadius, strokePaint)
            }
        }
    }
}

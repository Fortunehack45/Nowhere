package com.fakegps.mocklocation.util

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R

/**
 * Hardware-accelerated frosted glass: window backdrop blur for sheets/dialogs,
 * plus downsampled stack-blur snapshots for in-app HUD cards over the map.
 */
object FrostedGlassManager {

    const val CAPTURE_INTERVAL_MS = 90L
    private const val SAMPLE_SCALE = 0.14f
    private const val STACK_BLUR_RADIUS = 12

    fun applyWindowBlur(dialog: Dialog?, radiusDp: Int = 32) {
        val window = dialog?.window ?: return
        applyWindowBlur(window, radiusDp)
    }

    fun applyWindowBlur(window: Window, radiusDp: Int = 32) {
        try {
            window.setDimAmount(0.28f)
            window.setBackgroundDrawableResource(android.R.color.transparent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val density = window.context.resources.displayMetrics.density
                val blurPx = (radiusDp * density).toInt().coerceIn(16, 128)

                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = blurPx
                window.attributes = params

                try {
                    window.setBackgroundBlurRadius(blurPx)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun applyViewBlur(view: View?, radiusDp: Float = 28f) {
        if (view == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val density = view.resources.displayMetrics.density
                val blurPx = radiusDp * density
                val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                view.setRenderEffect(effect)
            }
        } catch (_: Exception) {
        }
    }

    fun clearViewBlur(view: View?) {
        if (view == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(null)
            }
        } catch (_: Exception) {
        }
    }

    fun glassTintColor(context: Context): Int {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#99141418") else Color.parseColor("#A6F7F7FA")
    }

    /**
     * Captures the [source] region sitting behind [target], downsamples it, and stack-blurs
     * the result so HUD chrome can paint a real frosted plate instead of a transparent wash.
     */
    fun captureBlurredRegion(source: View, target: View): Bitmap? {
        return try {
            val tw = target.width
            val th = target.height
            if (tw <= 4 || th <= 4) return null
            if (source.width <= 4 || source.height <= 4) return null

            val sourceLoc = IntArray(2)
            val targetLoc = IntArray(2)
            source.getLocationOnScreen(sourceLoc)
            target.getLocationOnScreen(targetLoc)
            val dx = (targetLoc[0] - sourceLoc[0]).toFloat()
            val dy = (targetLoc[1] - sourceLoc[1]).toFloat()

            val bw = (tw * SAMPLE_SCALE).toInt().coerceAtLeast(12)
            val bh = (th * SAMPLE_SCALE).toInt().coerceAtLeast(12)
            val raw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(raw)
            canvas.scale(SAMPLE_SCALE, SAMPLE_SCALE)
            canvas.translate(-dx, -dy)
            source.draw(canvas)
            stackBlurInPlace(raw, STACK_BLUR_RADIUS)
            raw
        } catch (_: Exception) {
            null
        }
    }

    fun captureBlurredView(source: View, maxWidth: Int = 540): Bitmap? {
        return try {
            if (source.width <= 4 || source.height <= 4) return null
            val scale = (maxWidth.toFloat() / source.width).coerceAtMost(0.28f).coerceAtLeast(0.08f)
            val bw = (source.width * scale).toInt().coerceAtLeast(16)
            val bh = (source.height * scale).toInt().coerceAtLeast(16)
            val raw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(raw)
            canvas.scale(scale, scale)
            source.draw(canvas)
            stackBlurInPlace(raw, STACK_BLUR_RADIUS + 4)
            raw
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compact Stack Blur (Mario Klingemann). Mutates [bitmap] in place.
     */
    fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(w.coerceAtLeast(h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + wm.coerceAtMost(i.coerceAtLeast(0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = (x + radius + 1).coerceAtMost(wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = 0.coerceAtLeast(yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = (y + r1).coerceAtMost(hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    fun fallbackGlassColor(context: Context): Int {
        return try {
            ContextCompat.getColor(context, R.color.surface_glass_card)
        } catch (_: Exception) {
            Color.parseColor("#A6FFFFFF")
        }
    }
}

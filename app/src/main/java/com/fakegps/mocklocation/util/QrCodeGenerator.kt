package com.fakegps.mocklocation.util

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {

    /**
     * Generates a high-contrast dark-mode QR code Bitmap with the Nowhere app logo embedded in the center.
     */
    fun generateQrBitmap(
        content: String,
        width: Int = 512,
        height: Int = 512,
        darkColor: Int = Color.parseColor("#F2F5F8"),
        lightColor: Int = Color.parseColor("#12151E"),
        context: Context? = null
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val matrixWidth = bitMatrix.width
            val matrixHeight = bitMatrix.height
            val pixels = IntArray(matrixWidth * matrixHeight)

            for (y in 0 until matrixHeight) {
                val offset = y * matrixWidth
                for (x in 0 until matrixWidth) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) darkColor else lightColor
                }
            }

            val qrBitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888)
            qrBitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight)

            // Embed Nowhere App Logo in center
            val combinedBitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBitmap)
            canvas.drawBitmap(qrBitmap, 0f, 0f, null)

            val logoSize = (matrixWidth * 0.24f).toInt().coerceAtLeast(40)
            val logoLeft = (matrixWidth - logoSize) / 2f
            val logoTop = (matrixHeight - logoSize) / 2f
            val logoRect = RectF(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)

            // 1. Draw rounded background badge with subtle red border
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#181B26")
                style = Paint.Style.FILL
            }
            val cornerRadius = logoSize * 0.28f
            canvas.drawRoundRect(logoRect, cornerRadius, cornerRadius, bgPaint)

            val primaryColor = if (context != null) ThemeColorManager.getPrimaryColor(context) else Color.parseColor("#FF3B30")
            val darkColor = if (context != null) ThemeColorManager.getDarkColor(context) else primaryColor

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryColor
                style = Paint.Style.STROKE
                strokeWidth = (logoSize * 0.06f).coerceAtLeast(2f)
            }
            canvas.drawRoundRect(logoRect, cornerRadius, cornerRadius, strokePaint)

            // 2. Draw Nowhere Themed Logo in center
            var logoDrawn = false
            if (context != null) {
                try {
                    val drawable = ThemeColorManager.getThemedLogoDrawable(context, primaryColor, darkColor)
                    val iconPadding = (logoSize * 0.16f).toInt()
                    drawable.setBounds(
                        (logoLeft + iconPadding).toInt(),
                        (logoTop + iconPadding).toInt(),
                        (logoLeft + logoSize - iconPadding).toInt(),
                        (logoTop + logoSize - iconPadding).toInt()
                    )
                    drawable.draw(canvas)
                    logoDrawn = true
                } catch (ignored: Exception) {}
            }

            if (!logoDrawn) {
                // Vector fallback pin icon
                val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF3B30")
                    style = Paint.Style.FILL
                }
                val cx = matrixWidth / 2f
                val cy = matrixHeight / 2f
                val r = logoSize * 0.25f
                canvas.drawCircle(cx, cy, r, pinPaint)

                val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#181B26")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, r * 0.45f, innerPaint)
            }

            combinedBitmap
        } catch (e: Exception) {
            null
        }
    }
}

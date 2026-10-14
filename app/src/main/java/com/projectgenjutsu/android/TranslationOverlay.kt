package com.projectgenjutsu.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

data class PositionedTranslation(val bounds: Rect, val text: String)

/**
 * Два раздельных окна: текстовое — некликабельное; круглая кнопка — интерактивная.
 * alpha=0.75 у полноэкранного окна необходима для пропуска касаний на Android 12+.
 */
class TranslationOverlay(private val context: Context, private val scan: () -> Unit) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var canvasView: RenderView? = null
    private var scanButton: TextView? = null
    private var captureWidth = 1
    private var captureHeight = 1

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun attach() {
        if (canvasView != null) return
        canvasView = RenderView(context).also { view ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // В Android 12+ opaque NOT_TOUCHABLE overlays могут блокировать нажатия.
                alpha = 0.75f
            }
            manager.addView(view, params)
        }
        scanButton = TextView(context).also { view ->
            view.text = "文A"
            view.gravity = Gravity.CENTER
            view.textSize = 17f
            view.setTextColor(Color.WHITE)
            view.setBackgroundColor(0xFF7C4DFF.toInt())
            view.setOnClickListener { scan() }
            val params = WindowManager.LayoutParams(
                dp(58), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                x = dp(8)
            }
            manager.addView(view, params)
        }
    }

    fun hideForCapture() {
        canvasView?.visibility = View.INVISIBLE
        scanButton?.visibility = View.INVISIBLE
    }

    fun showControls() {
        scanButton?.visibility = View.VISIBLE
        canvasView?.visibility = View.VISIBLE
    }

    fun setTranslations(items: List<PositionedTranslation>, screenWidth: Int, screenHeight: Int) {
        captureWidth = max(1, screenWidth)
        captureHeight = max(1, screenHeight)
        canvasView?.render(items, captureWidth, captureHeight)
        showControls()
    }

    fun close() {
        scanButton?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }
        canvasView?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }
        scanButton = null
        canvasView = null
    }

    private class RenderView(context: Context) : View(context) {
        private var blocks: List<PositionedTranslation> = emptyList()
        private var sourceWidth: Int = 1
        private var sourceHeight: Int = 1
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF151824.toInt()
        }
        private val font = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * resources.displayMetrics.scaledDensity
        }
        private val pad = (7 * resources.displayMetrics.density)

        fun render(data: List<PositionedTranslation>, width: Int, height: Int) {
            blocks = data
            sourceWidth = max(1, width)
            sourceHeight = max(1, height)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scaleX = width.toFloat() / sourceWidth
            val scaleY = height.toFloat() / sourceHeight
            for (block in blocks) {
                if (block.text.isBlank()) continue
                val x = (block.bounds.left * scaleX).coerceAtLeast(0f)
                val y = (block.bounds.top * scaleY).coerceAtLeast(0f)
                val desiredWidth = max(block.bounds.width() * scaleX, 180f * resources.displayMetrics.density)
                val boxWidth = min(desiredWidth, max(70f, width.toFloat() - 2f * pad))
                val textWidth = max(1, (boxWidth - 2f * pad).toInt())
                val layout = StaticLayout.Builder.obtain(block.text, 0, block.text.length, font, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
                val boxHeight = layout.height + 2f * pad
                val left = min(x, max(0f, width - boxWidth))
                val top = min(y, max(0f, height - boxHeight))
                val rect = RectF(left, top, left + boxWidth, top + boxHeight)
                canvas.drawRoundRect(rect, pad, pad, background)
                canvas.save()
                canvas.translate(left + pad, top + pad)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}

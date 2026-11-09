package com.tharunbirla.librecuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Cricket Score Card (Step 1).
 *
 * Flow: pick a scoreboard screenshot -> drag the 4 edges to crop it to the rows you
 * want -> "Add" renders a rounded, coloured-border card PNG and returns it to
 * VideoEditingActivity, which drops it on the timeline as a normal image overlay.
 *
 * Built entirely in code (no XML layout) so it adds no resource dependencies.
 */
class CricketScoreCardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "cric_type"            // "bat" or "bowl"
        const val RESULT_URI = "cric_result_uri"      // String uri of rendered PNG
        const val RESULT_ASPECT = "cric_result_aspect" // Float width / height of the card
        const val TYPE_BAT = "bat"
        const val TYPE_BOWL = "bowl"

        fun createIntent(context: Context, type: String): Intent =
            Intent(context, CricketScoreCardActivity::class.java).putExtra(EXTRA_TYPE, type)
    }

    private lateinit var cropView: ScoreCropView
    private var sourceBitmap: Bitmap? = null
    private var type: String = TYPE_BAT

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            loadBitmap(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_BAT
        buildUi()
        if (savedInstanceState == null) {
            pickImage.launch(arrayOf("image/*"))
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = if (type == TYPE_BOWL) "BOWL CARD - crop the rows" else "SCORE CARD - crop the rows"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(40), dp(12), dp(12))
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        cropView = ScoreCropView(this, borderColor())
        val cropHolder = FrameLayout(this)
        cropHolder.addView(cropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cropHolder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            gravity = Gravity.CENTER
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        val add = Button(this).apply {
            text = "Add to video"
            setOnClickListener { renderAndReturn() }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun borderColor(): Int =
        if (type == TYPE_BOWL) Color.parseColor("#76FF03") else Color.parseColor("#00E5FF")

    private fun loadBitmap(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null) {
                Toast.makeText(this, "Could not open that image", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }
            sourceBitmap = bmp
            cropView.setBitmap(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun renderAndReturn() {
        val src = sourceBitmap
        if (src == null) {
            Toast.makeText(this, "Pick a screenshot first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val crop = cropView.getCropInBitmapPixels()
            val cw = crop.width().toInt().coerceAtLeast(2)
            val ch = crop.height().toInt().coerceAtLeast(2)
            val cropped = Bitmap.createBitmap(src, crop.left.toInt(), crop.top.toInt(), cw, ch)

            // Card = cropped rows inside a rounded rectangle with a coloured border.
            val border = max(6, cw / 150)
            val radius = border * 3f
            val outW = cw + border * 2
            val outH = ch + border * 2
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = borderColor() }
            canvas.drawRoundRect(RectF(0f, 0f, outW.toFloat(), outH.toFloat()), radius, radius, borderPaint)

            val inner = RectF(border.toFloat(), border.toFloat(), (outW - border).toFloat(), (outH - border).toFloat())
            val clip = Path().apply { addRoundRect(inner, radius * 0.6f, radius * 0.6f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(cropped, border.toFloat(), border.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()

            val dir = File(cacheDir, "scorecards").apply { mkdirs() }
            val file = File(dir, "scorecard_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val result = Intent()
                .putExtra(RESULT_URI, Uri.fromFile(file).toString())
                .putExtra(RESULT_ASPECT, outW.toFloat() / outH.toFloat())
            setResult(Activity.RESULT_OK, result)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Render failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows the screenshot fit-to-view with a rectangle whose 4 edges can be dragged. */
    private class ScoreCropView(context: Context, private val accent: Int) : View(context) {

        private var bmp: Bitmap? = null
        private val imageRect = RectF()
        private val crop = RectF()
        private val minSize = 60f
        private val touchSlop = 56f

        private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var dragLeft = false
        private var dragRight = false
        private var dragTop = false
        private var dragBottom = false

        fun setBitmap(b: Bitmap) {
            bmp = b
            layoutImage()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutImage()
        }

        private fun layoutImage() {
            val b = bmp ?: return
            if (width == 0 || height == 0) return
            val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale
            val h = b.height * scale
            val l = (width - w) / 2f
            val t = (height - h) / 2f
            imageRect.set(l, t, l + w, t + h)
            crop.set(imageRect)
        }

        /** Crop rectangle converted from screen coordinates to bitmap pixels. */
        fun getCropInBitmapPixels(): RectF {
            val b = bmp ?: return RectF(0f, 0f, 2f, 2f)
            val sx = b.width / imageRect.width()
            val sy = b.height / imageRect.height()
            val l = ((crop.left - imageRect.left) * sx).coerceIn(0f, b.width - 2f)
            val t = ((crop.top - imageRect.top) * sy).coerceIn(0f, b.height - 2f)
            val r = ((crop.right - imageRect.left) * sx).coerceIn(l + 2f, b.width.toFloat())
            val bt = ((crop.bottom - imageRect.top) * sy).coerceIn(t + 2f, b.height.toFloat())
            return RectF(l, t, r, bt)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            canvas.drawBitmap(b, null, imageRect, bmpPaint)

            // Dim everything outside the crop rectangle.
            canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, crop.top, dimPaint)
            canvas.drawRect(imageRect.left, crop.bottom, imageRect.right, imageRect.bottom, dimPaint)
            canvas.drawRect(imageRect.left, crop.top, crop.left, crop.bottom, dimPaint)
            canvas.drawRect(crop.right, crop.top, imageRect.right, crop.bottom, dimPaint)

            canvas.drawRect(crop, linePaint)
            val cx = crop.centerX()
            val cy = crop.centerY()
            canvas.drawCircle(cx, crop.top, 14f, handlePaint)
            canvas.drawCircle(cx, crop.bottom, 14f, handlePaint)
            canvas.drawCircle(crop.left, cy, 14f, handlePaint)
            canvas.drawCircle(crop.right, cy, 14f, handlePaint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragLeft = kotlin.math.abs(e.x - crop.left) < touchSlop
                    dragRight = kotlin.math.abs(e.x - crop.right) < touchSlop
                    dragTop = kotlin.math.abs(e.y - crop.top) < touchSlop
                    dragBottom = kotlin.math.abs(e.y - crop.bottom) < touchSlop
                    // Prefer the nearer edge if both opposite edges are in range (tiny crop).
                    if (dragLeft && dragRight) {
                        if (kotlin.math.abs(e.x - crop.left) <= kotlin.math.abs(e.x - crop.right)) dragRight = false else dragLeft = false
                    }
                    if (dragTop && dragBottom) {
                        if (kotlin.math.abs(e.y - crop.top) <= kotlin.math.abs(e.y - crop.bottom)) dragBottom = false else dragTop = false
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return dragLeft || dragRight || dragTop || dragBottom
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragLeft) crop.left = e.x.coerceIn(imageRect.left, crop.right - minSize)
                    if (dragRight) crop.right = e.x.coerceIn(crop.left + minSize, imageRect.right)
                    if (dragTop) crop.top = e.y.coerceIn(imageRect.top, crop.bottom - minSize)
                    if (dragBottom) crop.bottom = e.y.coerceIn(crop.top + minSize, imageRect.bottom)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragLeft = false; dragRight = false; dragTop = false; dragBottom = false
                    return true
                }
            }
            return true
        }
    }
}

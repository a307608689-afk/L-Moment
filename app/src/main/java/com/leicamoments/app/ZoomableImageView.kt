package com.leicamoments.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.OverScroller
import kotlin.math.roundToInt

/**
 * 可捏合缩放的预览图（相册级手感）：
 * - 单一 Matrix 状态（base=fitCenter 基准 + zoom=手势矩阵），缩放/平移/钳制互不打架
 * - 捏合以双指焦点为锚点；双指平移、单指拖动、松手惯性 fling
 * - 双击动画式放大/复位；长按对比原图（回调交由宿主换图）
 * - 手势期间挂硬件层，避免大位图逐帧重绘的迟滞感
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageView(context, attrs) {

    /** 长按进入对比原图（宿主换 bitmap）；松手自动回调 [onCompareExit] */
    var onCompareEnter: (() -> Unit)? = null
    var onCompareExit: (() -> Unit)? = null
    private var comparing = false

    var maxScale = 6f
    var doubleTapScale = 2f

    private val mValues = FloatArray(9)
    private val baseMatrix = Matrix()      // fitCenter：位图适配视图
    private val zoomMatrix = Matrix()      // 用户手势
    private val drawMatrix = Matrix()
    private var minScale = 1f

    private val scroller = OverScroller(context)
    private var flingRunning = false
    private var flingLastX = 0
    private var flingLastY = 0
    private val flingStep = object : Runnable {
        override fun run() { advanceFling() }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector by lazy {
        ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    if (drawable == null) return false
                    cancelAnimations()
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    return true
                }

                override fun onScaleEnd(d: ScaleGestureDetector) {
                    setLayerType(View.LAYER_TYPE_NONE, null)
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    var f = d.scaleFactor
                    if (!f.isFinite() || f <= 0f) return true
                    // 目标总缩放越界时把因子折回边界：锚点仍生效，倍率不越界
                    val cur = currentScale() * f
                    if (cur > maxScale) f *= maxScale / cur
                    else if (cur < minScale) f *= minScale / cur
                    if (!f.isFinite() || f <= 0f) return true
                    // 以焦点为锚：postScale 天然包含双指平移（焦点移动带动内容）
                    zoomMatrix.postScale(f, f, d.focusX, d.focusY)
                    applyMatrix()
                    return true
                }
            })
    }

    private val gestureDetector by lazy {
        GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (scaleDetector.isInProgress || drawable == null) return false
                    zoomMatrix.postTranslate(-dx, -dy)
                    applyMatrix()
                    return true
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (scaleDetector.isInProgress || drawable == null) return false
                    zoomMatrix.getValues(mValues)
                    flingLastX = mValues[Matrix.MTRANS_X].roundToInt()
                    flingLastY = mValues[Matrix.MTRANS_Y].roundToInt()
                    scroller.fling(
                        flingLastX, flingLastY,
                        -vx.roundToInt(), -vy.roundToInt(),
                        -1_000_000, 1_000_000, -1_000_000, 1_000_000
                    )
                    flingRunning = true
                    postOnAnimation(flingStep)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (drawable == null) return true
                    val zoomed = currentScale() > minScale * 1.05f
                    animateTo(
                        if (zoomed) Matrix().apply { reset() }
                        else Matrix().apply { postScale(doubleTapScale, doubleTapScale, e.x, e.y) }
                    )
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // GestureDetector 不感知 POINTER_DOWN，捏合开始瞬间不可触发对比
                    if (scaleDetector.isInProgress) return
                    if (drawable != null && !comparing) {
                        comparing = true
                        onCompareEnter?.invoke()
                    }
                }
            })
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    // ---- 对外 ----

    /** 复位缩放/平移（换图、切滤镜时调用） */
    fun resetZoom() {
        cancelAnimations()
        zoomMatrix.reset()
        applyMatrix()
    }

    // ---- 位图与尺寸变化：重建基准矩阵（fitCenter）----

    override fun setImageBitmap(bm: Bitmap?) {
        val prev = drawable
        val sameSize = bm != null && prev != null &&
            prev.intrinsicWidth == bm.width && prev.intrinsicHeight == bm.height
        super.setImageBitmap(bm)
        // 同尺寸换图（滤镜/对比切换）保留缩放状态；新图（尺寸变化）自动复位
        updateBaseMatrix(resetZoom = !sameSize)
    }

    override fun setImageDrawable(d: Drawable?) {
        val prev = drawable
        val sameSize = d != null && prev != null &&
            prev.intrinsicWidth == d.intrinsicWidth && prev.intrinsicHeight == d.intrinsicHeight
        super.setImageDrawable(d)
        updateBaseMatrix(resetZoom = !sameSize)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix(resetZoom = false)
    }

    private fun updateBaseMatrix(resetZoom: Boolean) {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f || width <= 0 || height <= 0) return
        baseMatrix.reset()
        val s = minOf(width / dw, height / dh)
        baseMatrix.setScale(s, s)
        baseMatrix.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        minScale = s
        if (resetZoom) zoomMatrix.reset()
        applyMatrix()
    }

    // ---- 触摸 ----

    @Deprecated("Deprecated in Java")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                flingRunning = false
                removeCallbacks(flingStep)
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 捏合中抬一指：重置单指平移基准，防跳变
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                // 单指平移交给 GestureDetector.onScroll；此处只兜底双指未缩放时的平移
                if (event.pointerCount >= 2) {
                    zoomMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x; lastY = event.y
                    applyMatrix()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (comparing) {
                    comparing = false
                    onCompareExit?.invoke()
                }
            }
        }
        return true
    }

    // ---- 矩阵应用与边界钳制 ----

    /** @return true 表示发生了边界修正（fling 据此停止） */
    private fun applyMatrix(): Boolean {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(zoomMatrix)
        var clamped = false
        val d = drawable
        if (d != null && width > 0 && height > 0) {
            val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            drawMatrix.mapRect(rect)
            val vw = width.toFloat(); val vh = height.toFloat()
            val dx = when {
                rect.width() >= vw ->
                    if (rect.left > 0f) -rect.left
                    else if (rect.right < vw) vw - rect.right
                    else 0f
                else -> (vw - rect.width()) / 2f - rect.left
            }
            val dy = when {
                rect.height() >= vh ->
                    if (rect.top > 0f) -rect.top
                    else if (rect.bottom < vh) vh - rect.bottom
                    else 0f
                else -> (vh - rect.height()) / 2f - rect.top
            }
            if (dx != 0f || dy != 0f) {
                drawMatrix.postTranslate(dx, dy)
                zoomMatrix.postTranslate(dx, dy)
                clamped = true
            }
        }
        imageMatrix = drawMatrix
        return clamped
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(mValues)
        return mValues[Matrix.MSCALE_X]
    }

    // ---- 惯性 ----

    private fun advanceFling() {
        if (!flingRunning) return
        if (scroller.computeScrollOffset()) {
            val tx = scroller.currX
            val ty = scroller.currY
            zoomMatrix.postTranslate((tx - flingLastX).toFloat(), (ty - flingLastY).toFloat())
            flingLastX = tx; flingLastY = ty
            if (applyMatrix()) {          // 触到边界：停
                scroller.forceFinished(true)
                flingRunning = false
            } else {
                postOnAnimation(flingStep)
            }
        } else {
            flingRunning = false
        }
    }

    // ---- 双击动画 ----

    /** 把 zoomMatrix 动画过渡到目标矩阵（值线性插值，同旋向下观感平滑） */
    private fun animateTo(end: Matrix) {
        cancelAnimations()
        val start = Matrix(zoomMatrix)
        val a = FloatArray(9); val b = FloatArray(9)
        start.getValues(a); end.getValues(b)
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                val out = FloatArray(9)
                for (i in 0..8) out[i] = a[i] + (b[i] - a[i]) * v
                zoomMatrix.setValues(out)
                applyMatrix()
            }
            start()
        }
    }

    private fun cancelAnimations() {
        zoomAnimator?.cancel(); zoomAnimator = null
        scroller.forceFinished(true)
        flingRunning = false
        removeCallbacks(flingStep)
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        setLayerType(View.LAYER_TYPE_NONE, null)
        super.onDetachedFromWindow()
    }
}

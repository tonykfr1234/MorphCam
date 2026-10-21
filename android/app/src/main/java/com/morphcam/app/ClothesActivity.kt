package com.morphcam.app

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.Executors

/** On-device AR clothing: pose tracking + garments bound to the skeleton. */
class ClothesActivity : ComponentActivity() {
    private lateinit var view: FrameView
    private var landmarker: PoseLandmarker? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var useFront = true
    private var smooth: Array<PointF?>? = null

    private val pickPng = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) toast("無法讀取圖片") else view.garment = ImageGarment("自訂", bmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = FrameView(this).apply { garment = Wardrobe.builtIn.first() }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x99000000.toInt())
            Wardrobe.builtIn.forEach { g -> addView(button(g.name) { view.garment = g }) }
            addView(button("自訂PNG…") { pickPng.launch("image/png") })
            addView(button("不穿") { view.garment = null })
            addView(button("切換鏡頭") { useFront = !useFront; smooth = null; startCamera() })
        }
        val root = FrameLayout(this).apply {
            addView(view)
            addView(HorizontalScrollView(this@ClothesActivity).apply { addView(bar) },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)

        exec.execute { initLandmarker() }
        if (ensurePermission(Manifest.permission.CAMERA, 1)) startCamera()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == 0) startCamera() else toast("需要相機權限")
    }

    private fun initLandmarker() {
        fun build(delegate: Delegate) = PoseLandmarker.createFromOptions(
            this,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_full.task").setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
        landmarker = runCatching { build(Delegate.GPU) }.getOrElse { build(Delegate.CPU) }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            val front = useFront
            analysis.setAnalyzer(exec) { proxy ->
                val bmp = proxy.use { it.toUprightBitmap(mirror = front, maxSide = 720) }
                val lm = landmarker
                if (lm == null) {
                    view.show(bmp)
                    return@setAnalyzer
                }
                val result = lm.detectForVideo(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
                val pose = result.landmarks().firstOrNull()
                if (pose == null) {
                    smooth = null
                    view.show(bmp)
                    return@setAnalyzer
                }
                val raw = Array(pose.size) { i ->
                    val p = pose[i]
                    val vis = p.visibility().orElse(1f)
                    if (vis < 0.5f) null else PointF(p.x(), p.y())
                }
                view.show(bmp, smoothen(raw))
            }
            provider.unbindAll()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Exponential smoothing removes jitter so clothes feel attached, not floating. */
    private fun smoothen(raw: Array<PointF?>): Array<PointF?> {
        val prev = smooth
        val out = Array(raw.size) { i ->
            val r = raw[i] ?: return@Array null
            val p = prev?.getOrNull(i) ?: return@Array r
            // Move faster when the jump is big so fast motion does not lag.
            val d = kotlin.math.hypot(r.x - p.x, r.y - p.y)
            val a = (0.35f + d * 6f).coerceAtMost(1f)
            PointF(p.x + (r.x - p.x) * a, p.y + (r.y - p.y) * a)
        }
        smooth = out
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.execute { landmarker?.close() }
        exec.shutdown()
    }
}

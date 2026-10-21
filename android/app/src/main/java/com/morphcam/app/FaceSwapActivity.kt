package com.morphcam.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Streams camera frames to the PC, shows the face-swapped frames that come back. */
class FaceSwapActivity : ComponentActivity() {
    private lateinit var view: FrameView
    private lateinit var status: android.widget.TextView
    private val exec = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var useFront = true
    private var frames = 0
    private var fpsT0 = System.currentTimeMillis()

    private val pickFace = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
        uploadFace(bytes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = FrameView(this)
        status = label("連線中…", 13f)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(button("選擇要變成的臉…") { pickFace.launch("image/*") }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("原本的臉") { selectFace(null) })
            addView(button("切換鏡頭") { useFront = !useFront; startCamera() })
        }
        setContentView(FrameLayout(this).apply {
            addView(view)
            addView(aiBadge(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
            addView(status.apply { setPadding(dp(12), dp(12), dp(12), dp(12)) },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        connect()
        if (ensurePermission(Manifest.permission.CAMERA, 1)) startCamera()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == 0) startCamera() else toast("需要相機權限")
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun connect() {
        val req = Request.Builder().url(Net.ws(this, "/ws/video")).build()
        ws = Net.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                setStatus("已連線（第一次會載入模型，請稍候）")
                Prefs.faceId(this@FaceSwapActivity)?.let { selectFace(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inFlight.set(false)
                val arr = bytes.toByteArray()
                BitmapFactory.decodeByteArray(arr, 0, arr.size)?.let { view.show(it) }
                frames++
                val now = System.currentTimeMillis()
                if (now - fpsT0 >= 1000) {
                    setStatus("換臉中  ${frames * 1000 / (now - fpsT0)} fps")
                    frames = 0; fpsT0 = now
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = JSONObject(text)
                if (o.has("error")) setStatus("伺服器錯誤：" + o.getString("error"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); inFlight.set(false)
                setStatus("連線失敗：${t.message}\n3 秒後重試…")
                view.postDelayed({ if (!isFinishing) connect() }, 3000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
            }
        })
    }

    private fun selectFace(id: String?) {
        Prefs.setFaceId(this, id)
        ws?.send(JSONObject().put("face_id", id ?: JSONObject.NULL).toString())
    }

    private fun uploadFace(raw: ByteArray) {
        setStatus("上傳照片中…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Downscale before upload; 1024px is plenty for face embedding.
                    val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("無法讀取圖片")
                    val s = minOf(1f, 1024f / maxOf(src.width, src.height))
                    val bmp = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
                    val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "face.jpg", jpg.toRequestBody("image/jpeg".toMediaType()))
                        .build()
                    Net.http.newCall(Request.Builder().url(Net.url(this@FaceSwapActivity, "/faces")).post(body).build())
                        .execute().use { r ->
                            val txt = r.body?.string()
                            if (!r.isSuccessful) error(Net.errorText(r.code, txt))
                            JSONObject(txt!!).getString("id")
                        }
                }
            }
            result.onSuccess { selectFace(it); setStatus("已套用新的臉") }
                .onFailure { setStatus("上傳失敗：${it.message}") }
        }
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
            val out = ByteArrayOutputStream()
            analysis.setAnalyzer(exec) { proxy ->
                val bmp = proxy.use { it.toUprightBitmap(mirror = front, maxSide = 640) }
                if (!connected.get()) {
                    view.show(bmp)   // offline: plain preview
                    return@setAnalyzer
                }
                // One frame in flight at a time keeps latency low on Wi-Fi.
                if (inFlight.compareAndSet(false, true)) {
                    out.reset()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    if (ws?.send(out.toByteArray().toByteString()) != true) inFlight.set(false)
                }
            }
            provider.unbindAll()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, null)
        exec.shutdown()
    }
}

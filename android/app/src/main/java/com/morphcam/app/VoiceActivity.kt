package com.morphcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val SR = 16000

/**
 * 1) Enroll a voice: the voice's owner reads a consent phrase, plus a reference clip.
 * 2) Live conversion: mic -> PC -> converted voice -> speaker/headphones.
 */
class VoiceActivity : ComponentActivity() {
    private lateinit var nameInput: EditText
    private lateinit var phraseView: TextView
    private lateinit var enrollStatus: TextView
    private lateinit var recordBtn: Button
    private lateinit var liveBtn: Button
    private lateinit var liveStatus: TextView
    private lateinit var voiceSpinner: Spinner
    private var voices = listOf<Pair<String, String>>()   // id to name

    private var consentWav: ByteArray? = null
    private var referenceUri: Uri? = null
    @Volatile private var recordingConsent = false
    @Volatile private var live = false
    private var ws: WebSocket? = null
    private var track: AudioTrack? = null

    private val pickRef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        referenceUri = uri
        if (uri != null) enrollStatus.text = "已選擇參考聲音檔"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nameInput = EditText(this).apply { hint = "聲音主人的名字"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        phraseView = label("", 17f, 0xFFFFE082.toInt())
        enrollStatus = label("", 14f, Color.LTGRAY)
        recordBtn = button("② 開始錄同意聲明") { toggleConsentRecording() }
        liveStatus = label("", 14f, Color.LTGRAY)
        voiceSpinner = Spinner(this)
        liveBtn = button("▶ 開始即時變聲") { toggleLive() }

        val root = vbox(
            label("建立聲音", 22f),
            label("必須由聲音本人親自錄製同意聲明。伺服器會核對聲明內容，並比對錄音和參考聲音是不是同一個人，兩項都通過才能建立。",
                13f, Color.GRAY),
            nameInput,
            button("① 取得同意聲明") { fetchPhrase() },
            phraseView,
            recordBtn,
            button("③ 選擇參考聲音檔（本人說話 10–25 秒）") { pickRef.launch("audio/*") },
            button("④ 上傳並建立聲音") { enroll() },
            enrollStatus,
            label("\n即時變聲", 22f),
            label("請戴耳機，不然喇叭的聲音會被麥克風收回去。延遲約 1–1.5 秒。", 13f, Color.GRAY),
            hbox(voiceSpinner, button("重新整理") { loadVoices() }),
            liveBtn,
            liveStatus,
        )
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF111418.toInt()); addView(root) })
        loadVoices()
    }

    // ---------------------------------------------------------------- enroll
    private fun fetchPhrase() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { toast("請先輸入名字"); return }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val url = Net.url(this@VoiceActivity, "/consent_phrase").toHttpUrl().newBuilder()
                        .addQueryParameter("name", name).build()
                    Net.http.newCall(Request.Builder().url(url).build()).execute().use {
                        JSONObject(it.body!!.string()).getString("phrase")
                    }
                }
            }
            res.onSuccess { phraseView.text = "請本人念：\n「$it」" }.onFailure { toast("連不上伺服器：${it.message}") }
        }
    }

    private fun toggleConsentRecording() {
        if (recordingConsent) { recordingConsent = false; return }
        if (!ensurePermission(Manifest.permission.RECORD_AUDIO, 2)) return
        if (phraseView.text.isNullOrEmpty()) { toast("請先取得同意聲明"); return }
        recordingConsent = true
        recordBtn.text = "■ 停止錄音"
        enrollStatus.text = "錄音中…念完後按停止"
        thread {
            val pcm = recordUntil { recordingConsent }
            consentWav = Wav.encode(pcm, SR)
            runOnUiThread {
                recordBtn.text = "② 重新錄同意聲明"
                enrollStatus.text = "已錄好同意聲明（${pcm.size / 2 / SR} 秒）"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordUntil(keepGoing: () -> Boolean): ByteArray {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(3200)
        rec.startRecording()
        while (keepGoing() && out.size() < SR * 2 * 60) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) out.write(buf, 0, n)
        }
        rec.stop(); rec.release()
        return out.toByteArray()
    }

    private fun enroll() {
        val name = nameInput.text.toString().trim()
        val consent = consentWav
        val ref = referenceUri
        when {
            name.isEmpty() -> { toast("請輸入名字"); return }
            consent == null -> { toast("請先錄同意聲明"); return }
            ref == null -> { toast("請選擇參考聲音檔"); return }
        }
        enrollStatus.text = "上傳中，伺服器正在核對（第一次約需 1 分鐘）…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val refBytes = contentResolver.openInputStream(ref!!)!!.use { it.readBytes() }
                    val mime = contentResolver.getType(ref) ?: "application/octet-stream"
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("name", name)
                        .addFormDataPart("consent", "consent.wav", consent!!.toRequestBody("audio/wav".toMediaType()))
                        .addFormDataPart("reference", "reference.$ext", refBytes.toRequestBody(mime.toMediaType()))
                        .build()
                    Net.http.newCall(Request.Builder().url(Net.url(this@VoiceActivity, "/voices")).post(body).build())
                        .execute().use { r ->
                            val txt = r.body?.string()
                            if (!r.isSuccessful) error(Net.errorText(r.code, txt))
                            JSONObject(txt!!).getString("name")
                        }
                }
            }
            res.onSuccess { enrollStatus.text = "✅ 已建立「$it」的聲音"; loadVoices() }
                .onFailure { enrollStatus.text = "❌ 未通過：${it.message}" }
        }
    }

    private fun loadVoices() {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Net.http.newCall(Request.Builder().url(Net.url(this@VoiceActivity, "/voices")).build())
                        .execute().use { r ->
                            val a = JSONArray(r.body!!.string())
                            List(a.length()) { i -> a.getJSONObject(i).let { it.getString("id") to it.getString("name") } }
                        }
                }
            }
            res.onSuccess { list ->
                voices = list
                voiceSpinner.adapter = ArrayAdapter(this@VoiceActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    if (list.isEmpty()) listOf("（還沒有聲音）") else list.map { it.second })
            }.onFailure { liveStatus.text = "連不上伺服器：${it.message}" }
        }
    }

    // ---------------------------------------------------------------- live
    private fun toggleLive() {
        if (live) { stopLive(); return }
        if (!ensurePermission(Manifest.permission.RECORD_AUDIO, 2)) return
        val voice = voices.getOrNull(voiceSpinner.selectedItemPosition) ?: run { toast("請先建立聲音"); return }
        live = true
        liveBtn.text = "■ 停止"
        liveStatus.text = "連線中（第一次會載入模型，約 30 秒）…"

        val minOut = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minOut, SR * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }

        val url = Net.ws(this, "/ws/audio") + "?voice_id=" + voice.first
        ws = Net.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { liveStatus.text = "🎙 變聲中：${voice.second}" }
                thread { streamMic(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                track?.write(arr, 0, arr.size)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = JSONObject(text)
                if (o.has("error")) runOnUiThread { liveStatus.text = "伺服器錯誤：" + o.getString("error") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { liveStatus.text = "連線中斷：${t.message}"; stopLive() }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun streamMic(socket: WebSocket) {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR))
        val buf = ByteArray(SR / 10 * 2)   // 100 ms
        rec.startRecording()
        while (live) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0 && !socket.send(buf.copyOf(n).toByteString())) break
        }
        rec.stop(); rec.release()
    }

    private fun stopLive() {
        live = false
        ws?.close(1000, null); ws = null
        track?.run { stop(); release() }; track = null
        liveBtn.text = "▶ 開始即時變聲"
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingConsent = false
        stopLive()
    }
}

object Wav {
    fun encode(pcm: ByteArray, sr: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}

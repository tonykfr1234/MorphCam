package com.morphcam.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class MainActivity : ComponentActivity() {
    private lateinit var serverInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverInput = EditText(this).apply {
            setText(Prefs.server(this@MainActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        val status = label("")
        val root = vbox(
            label("MorphCam", 28f),
            label("即時換衣服・換臉・變聲", 15f, Color.LTGRAY),
            label("\n電腦伺服器位址（電腦執行 run.ps1 後畫面會顯示）"),
            serverInput,
            button("測試連線") { save(); testConnection(status) },
            status,
            label(""),
            button("👕  換衣服（手機本機運算）") { open(ClothesActivity::class.java) },
            button("🎭  換臉（需要電腦伺服器）") { open(FaceSwapActivity::class.java) },
            button("🎙  變聲／聲音複製（需要電腦伺服器）") { open(VoiceActivity::class.java) },
            label(
                "\n使用規範：換臉畫面一律加上「AI 生成」浮水印；複製聲音必須由聲音本人親自念同意聲明。" +
                    "請勿用於冒充他人、詐騙或未經同意的內容。",
                13f, Color.GRAY
            ),
        )
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF111418.toInt()); addView(root) })
    }

    private fun save() = Prefs.setServer(this, serverInput.text.toString())

    private fun open(cls: Class<*>) {
        save()
        startActivity(Intent(this, cls))
    }

    private fun testConnection(status: android.widget.TextView) {
        status.text = "連線中…"
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    Net.http.newCall(Request.Builder().url(Net.url(this@MainActivity, "/health")).build())
                        .execute().use { if (it.isSuccessful) "✅ 連線成功" else "❌ 伺服器回應 ${it.code}" }
                }.getOrElse { "❌ 連不上：${it.message}\n請確認手機和電腦在同一個 Wi-Fi，且電腦已執行 run.ps1" }
            }
            status.text = msg
        }
    }
}

package com.morphcam.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("morphcam", Context.MODE_PRIVATE)
    fun server(c: Context): String = sp(c).getString("server", "http://192.168.1.100:8765")!!
    fun setServer(c: Context, v: String) = sp(c).edit().putString("server", v.trim().trimEnd('/')).apply()
    fun faceId(c: Context): String? = sp(c).getString("face", null)
    fun setFaceId(c: Context, v: String?) = sp(c).edit().putString("face", v).apply()
}

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // first request loads models on the PC
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun url(c: Context, path: String) = Prefs.server(c) + path
    fun ws(c: Context, path: String) = url(c, path).replaceFirst("http", "ws")

    /** Turns a FastAPI error body into a readable message. */
    fun errorText(code: Int, body: String?): String {
        val detail = runCatching { JSONObject(body ?: "").get("detail") }.getOrNull()
        return when (detail) {
            is JSONObject -> detail.optString("reason", detail.toString())
            null -> "伺服器錯誤 $code"
            else -> detail.toString()
        }
    }
}

/** Camera frame -> upright bitmap as the user sees it (mirrored for the selfie camera). */
fun ImageProxy.toUprightBitmap(mirror: Boolean, maxSide: Int = 640): Bitmap {
    val src = toBitmap()
    val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
    val m = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
        if (mirror) postScale(-1f, 1f)
        postScale(scale, scale)
    }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

fun Activity.ensurePermission(perm: String, code: Int): Boolean {
    if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
    ActivityCompat.requestPermissions(this, arrayOf(perm), code)
    return false
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

// ---- tiny programmatic UI helpers (keeps the project free of XML layouts)
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.vbox(vararg children: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    children.forEach { addView(it) }
}

fun Context.hbox(vararg children: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    children.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
}

fun Context.label(text: String, size: Float = 15f, color: Int = Color.WHITE) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    setPadding(0, dp(6), 0, dp(6))
}

fun Context.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

/** Red "AI 生成" badge shown over any transformed output. */
fun Context.aiBadge() = TextView(this).apply {
    text = "AI 生成"
    textSize = 13f
    setTextColor(Color.WHITE)
    setBackgroundColor(0xCCD00000.toInt())
    setPadding(dp(8), dp(3), dp(8), dp(3))
}

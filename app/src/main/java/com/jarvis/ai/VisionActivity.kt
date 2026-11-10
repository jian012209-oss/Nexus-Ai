package com.jarvis.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * JARVIS vision: live camera with a HUD overlay. Ask "what's this?" and JARVIS
 * takes a snapshot, identifies it with the cloud AI, and answers out loud.
 * Follow-up questions ("what's it used for?") are about the same picture.
 */
class VisionActivity : AppCompatActivity(), JarvisCore.Listener {

    companion object {
        const val EXTRA_QUESTION = "question"
    }

    private lateinit var preview: PreviewView
    private lateinit var core: CoreView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private var imageCapture: ImageCapture? = null
    private var capturing = false
    private var useFront = false
    private val ui = Handler(Looper.getMainLooper())

    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            caption.text = "JARVIS needs camera permission to see. Allow it and open Vision again."
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisCore.init(this)
        val root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(this@VisionActivity, R.color.bg)) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // HUD overlay: brackets + small reactor
        val hud = CoreView(this).apply { label = ""; alpha = 0.55f }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), 0)
        }
        top.addView(TextView(this).apply {
            text = "J.A.R.V.I.S. · VISION"
            setTextColor(ContextCompat.getColor(this@VisionActivity, R.color.cyan))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.15f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        status = TextView(this).apply {
            text = "● SCANNER READY"
            setTextColor(ContextCompat.getColor(this@VisionActivity, R.color.hud_green))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.15f
        }
        top.addView(status)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        core = CoreView(this).apply { label = ""; showBrackets = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        caption = TextView(this).apply {
            text = "Point at something and say \"What's this?\", or tap Scan."
            setTextColor(ContextCompat.getColor(this@VisionActivity, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundResource(R.drawable.bg_glass)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 8
        }
        row.addView(core, LinearLayout.LayoutParams(dp(64), dp(64)))
        row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        bottom.addView(row)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@VisionActivity, R.color.cyan))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.1f
            setBackgroundResource(R.drawable.bg_quick)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { action() }
        }
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) } }
        buttons.addView(btn("SCAN") { capture("What is this? Identify it and tell me briefly what it's for.") }, lp())
        buttons.addView(btn("ASK") { askByVoice() }, lp())
        buttons.addView(btn("FLIP") { useFront = !useFront; startCamera() }, lp())
        buttons.addView(btn("CLOSE") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPermission.launch(Manifest.permission.CAMERA)

        intent.getStringExtra(EXTRA_QUESTION)?.takeIf { it.isNotBlank() }?.let { q ->
            ui.postDelayed({ capture(q) }, 1500)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(this, selector, pv, cap)
                imageCapture = cap
            } catch (e: Exception) {
                caption.text = "The camera couldn't start: ${e.message ?: "unknown error"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Takes a snapshot and asks JARVIS about it. */
    fun capture(question: String) {
        val cap = imageCapture
        if (cap == null) { ui.postDelayed({ capture(question) }, 700); return }
        if (capturing) return
        capturing = true
        status.text = "● SCANNING…"
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                image.close()
                Thread {
                    val jpeg = shrink(bytes, rotation)
                    runOnUiThread {
                        capturing = false
                        status.text = "● ANALYZING"
                        JarvisCore.askVision(question, jpeg)
                    }
                }.start()
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                status.text = "● SCANNER READY"
                caption.text = "I couldn't take the picture. Try again."
            }
        })
    }

    private fun shrink(jpeg: ByteArray, rotation: Int): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return jpeg
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } catch (e: Exception) { jpeg }
    }

    private fun askByVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            caption.text = "Allow the microphone in JARVIS to ask by voice."
            return
        }
        JarvisCore.listenOnce()
    }

    override fun onStart() {
        super.onStart()
        JarvisCore.visionActive = true
        JarvisCore.addListener(this)
    }

    override fun onStop() {
        JarvisCore.visionActive = false
        JarvisCore.removeListener(this)
        super.onStop()
    }

    override fun onState(state: JarvisCore.State) {
        core.mode = state
        if (state == JarvisCore.State.IDLE && !capturing) status.text = "● SCANNER READY"
        if (state == JarvisCore.State.LISTENING) status.text = "● LISTENING"
    }

    override fun onLevel(level: Float) = core.setLevel(level)

    override fun onPartial(text: String) {
        if (text.isNotBlank()) caption.text = text
    }

    override fun onMessage(fromUser: Boolean, text: String) {
        caption.text = if (fromUser) "YOU: $text" else text
    }

    override fun onVisionRequest(question: String) = capture(question)
}

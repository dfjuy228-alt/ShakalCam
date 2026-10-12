package com.example.shakalcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var processedView: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var status: TextView
    private var lastBitmap: Bitmap? = null
    @Volatile private var processing = false
    private var frontCamera = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else status.text = "Потрібен дозвіл на камеру"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 17, 22))
        }
        val header = TextView(this).apply {
            text = "SHAKALCAM  •  240p"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 12)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Kept attached behind the processed ImageView so CameraX can provide its surface.
        }
        processedView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "Live camera with low-quality effect"
        }
        cameraFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(processedView, FrameLayout.LayoutParams(-1, -1))
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "Ефекти: пікселі • шум • розмиття • JPEG-артефакти"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 16)
        }
        buttons.addView(makeButton("Зберегти фото") { saveLastFrame() },
            LinearLayout.LayoutParams(0, 52, 1f))
        buttons.addView(makeButton("Перевернути") {
            frontCamera = !frontCamera
            startCamera()
        }, LinearLayout.LayoutParams(0, 52, 1f))
        root.addView(buttons)
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                // 320x240 is the intended output size; camera hardware may select a nearby size.
                .setTargetResolution(android.util.Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                if (!processing) {
                    processing = true
                    try {
                        val source = imageProxyToBitmap(image)
                        val rotated = rotateBitmap(source, image.imageInfo.rotationDegrees)
                        val small = Bitmap.createScaledBitmap(rotated, 320, 240, false)
                        val effected = applyShakalEffects(small)
                        runOnUiThread {
                            processedView.setImageBitmap(effected)
                            lastBitmap = effected.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } catch (_: Exception) {
                        // A dropped frame is preferable to crashing the camera preview.
                    } finally {
                        image.close()
                        processing = false
                    }
                } else image.close()
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                status.text = "Ефекти активні • результат 320×240"
            } catch (e: Exception) {
                status.text = "Не вдалося запустити камеру: ${e.localizedMessage ?: "помилка"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        // ImageProxy chroma planes are usually interleaved but have pixel/row strides.
        // Convert with a stride-aware path for broad device compatibility.
        val w = image.width
        val h = image.height
        val out = IntArray(w * h)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = yBuf.get(row * yPlane.rowStride + col * yPlane.pixelStride).toInt() and 0xff
                val uvRow = row / 2
                val uvCol = col / 2
                val uu = (uBuf.get(uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride).toInt() and 0xff) - 128
                val vv = (vBuf.get(uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride).toInt() and 0xff) - 128
                val c = max(0, yy - 16)
                val r = (1.164f * c + 1.596f * vv).toInt().coerceIn(0, 255)
                val g = (1.164f * c - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255)
                val b = (1.164f * c + 2.017f * uu).toInt().coerceIn(0, 255)
                out[row * w + col] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyShakalEffects(input: Bitmap): Bitmap {
        // Force low-detail 160x120 sampling, then scale back to 320x240.
        val tiny = Bitmap.createScaledBitmap(input, 160, 120, false)
        val enlarged = Bitmap.createScaledBitmap(tiny, 320, 240, false)
        val pixels = IntArray(enlarged.width * enlarged.height)
        enlarged.getPixels(pixels, 0, enlarged.width, 0, 0, enlarged.width, enlarged.height)
        val rng = Random(System.nanoTime())
        for (i in pixels.indices) {
            val c = pixels[i]
            val noise = rng.nextInt(-13, 14)
            val r = (Color.red(c) + noise).coerceIn(0, 255)
            val g = (Color.green(c) + noise).coerceIn(0, 255)
            val b = (Color.blue(c) + noise).coerceIn(0, 255)
            // Slight color banding, similar to aggressively compressed video.
            pixels[i] = Color.rgb((r / 5) * 5, (g / 5) * 5, (b / 5) * 5)
        }
        val noisy = Bitmap.createBitmap(pixels, enlarged.width, enlarged.height, Bitmap.Config.ARGB_8888)
        val canvasBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val paint = Paint().apply {
            isFilterBitmap = false
            isDither = false
        }
        canvas.drawBitmap(noisy, 0f, 0f, paint)

        // JPEG compression artifacts, reintroduced by decoding a low-quality JPEG.
        val bytes = ByteArrayOutputStream()
        canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 22, bytes)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
        return decoded ?: canvasBitmap
    }

    private fun saveLastFrame() {
        val bitmap = lastBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Зачекай, поки з'явиться кадр", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ShakalCam_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ShakalCam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 28, it)
                }
                Toast.makeText(this, "Фото збережено в Pictures/ShakalCam", Toast.LENGTH_LONG).show()
            } else Toast.makeText(this, "Не вдалося зберегти фото", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка збереження: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

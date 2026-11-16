package com.s23ultra.focusstack

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var btnLens1x: Button
    private lateinit var btnLens06x: Button
    private lateinit var btnCapture: Button
    private lateinit var stepSeekBar: SeekBar
    private lateinit var stepLabel: TextView

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraId1x: String = "0"
    private var cameraId06x: String = "2"
    private var currentCameraId: String = "0"
    private var minFocusDist: Float = 10f
    private var sensorOrientation: Int = 90
    private var frameCount: Int = 12
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        detectS23UltraLenses()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun setupUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(40, 32, 40, 48)
        }

        statusText = TextView(this).apply {
            text = "Hazir - Lens secip cekime baslayin"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        controlsLayout.addView(statusText)

        // Kare sayisi secici
        stepLabel = TextView(this).apply {
            text = "Odak Adim Sayisi: 12 Kare"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        controlsLayout.addView(stepLabel)

        stepSeekBar = SeekBar(this).apply {
            max = 20 // 5 ile 25 arasi
            progress = 7
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    frameCount = progress + 5
                    stepLabel.text = "Odak Adim Sayisi: $frameCount Kare"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controlsLayout.addView(stepSeekBar)

        // Lens Secim Butonlari (0.6x ve 1x)
        val lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        btnLens06x = Button(this).apply {
            text = "0.6x Ultra Genis"
            setOnClickListener { switchLens(cameraId06x) }
        }
        btnLens1x = Button(this).apply {
            text = "1x Ana Lens"
            setOnClickListener { switchLens(cameraId1x) }
        }
        lensRow.addView(btnLens06x)
        lensRow.addView(btnLens1x)
        controlsLayout.addView(lensRow)

        btnCapture = Button(this).apply {
            text = "FOCUS STACK CEK & BIRLESTIR"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setOnClickListener {
                if (!isCapturing) startFocusStackCapture()
            }
        }
        controlsLayout.addView(btnCapture)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }

        root.addView(controlsLayout, params)
        setContentView(root)
    }

    private fun detectS23UltraLenses() {
        try {
            val allIds = mutableSetOf<String>()
            allIds.addAll(cameraManager.cameraIdList)
            // Mantiksal kameralarin altindaki fiziksel lens ID'lerini de tara
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    allIds.addAll(chars.physicalCameraIds)
                }
            }

            var widestFocal = Float.MAX_VALUE
            for (id in allIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
                    val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val f = focals.firstOrNull() ?: continue

                    if (f < widestFocal && f < 3.0f) {
                        widestFocal = f
                        cameraId06x = id
                    } else if (f in 5.0f..7.5f && minFocus > 0f && id == "0") {
                        cameraId1x = id
                    }
                } catch (_: Exception) {}
            }
            currentCameraId = cameraId1x
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun switchLens(targetId: String) {
        if (isCapturing) return
        currentCameraId = targetId
        closeCamera()
        openCamera()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val chars = cameraManager.getCameraCharacteristics(currentCameraId)
            minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // Hizli ve keskin birlestirme icin ~2560x1920 (5MP) veya en yakin 4:3 boyutunu sec
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            val chosenSize = sizes.filter { it.width in 1920..2800 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.first()

            imageReader = ImageReader.newInstance(chosenSize.width, chosenSize.height, ImageFormat.JPEG, 2)

            cameraManager.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(chosenSize)
                    runOnUiThread {
                        val lensName = if (currentCameraId == cameraId06x) "0.6x Ultra Genis" else "1x Ana Lens"
                        statusText.text = "Aktif: $lensName (ID:$currentCameraId) | Max Diyoptri: ${"%.1f".format(minFocusDist)} | ${chosenSize.width}x${chosenSize.height}"
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    runOnUiThread {
                        statusText.text = "Lens ID $currentCameraId dogrudan acilamadi, 1x'e donuluyor..."
                        if (currentCameraId != cameraId1x) switchLens(cameraId1x)
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            statusText.text = "Kamera hatasi: ${e.message}"
        }
    }

    private fun createPreviewSession(captureSize: Size) {
        val device = cameraDevice ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1280, 960)
        val previewSurface = Surface(surfaceTexture)
        val readerSurface = imageReader!!.surface

        val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        device.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun startFocusStackCapture() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        if (minFocusDist <= 0.1f) {
            Toast.makeText(this, "Bu lens manuel odak (LENS_FOCUS_DISTANCE) desteklemiyor!", Toast.LENGTH_LONG).show()
            return
        }

        isCapturing = true
        btnCapture.isEnabled = false

        lifecycleScope.launch {
            val capturedBitmaps = mutableListOf<Bitmap>()
            try {
                val previewSurface = Surface(textureView.surfaceTexture)
                val maxDiopter = minFocusDist
                val minDiopter = 0.15f // Sonsuz (0.15f ~ 6.6 metre ve otesi)

                for (i in 0 until frameCount) {
                    val t = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
                    // Alan derinligi fizigine uygun gama (yakin planda sIk, uzakta seyrek) odak dagilimi
                    val focusDiopter = maxDiopter - (maxDiopter - minDiopter) * t.pow(0.75f)

                    statusText.text = "Odaklaniyor ve Cekiliyor: ${i + 1} / $frameCount (${"%.2f".format(focusDiopter)} D)"

                    // 1. Once mercegi hedef odak noktasina tasi ve sarsintinin durmasini bekle
                    val moveBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    session.setRepeatingRequest(moveBuilder.build(), null, backgroundHandler)
                    delay(130) // Mercek motorunun (VCM) tam yerine oturmasi icin bekleme suresi

                    // 2. Fotograf karesini yakala
                    val frameDeferred = CompletableDeferred<Bitmap>()
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        img.close()
                        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val rotated = rotateBitmap(rawBmp, sensorOrientation.toFloat())
                        frameDeferred.complete(rotated)
                    }, backgroundHandler)

                    val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                    }
                    session.capture(stillBuilder.build(), null, backgroundHandler)
                    capturedBitmaps.add(frameDeferred.await())
                }

                // Onizlemeyi normale dondur
                val restoreBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                session.setRepeatingRequest(restoreBuilder.build(), null, backgroundHandler)

                // Laplacian Piramit Birlestirme Baslat
                val resultBitmap = FocusStackProcessor.processStack(capturedBitmaps) { msg ->
                    runOnUiThread { statusText.text = msg }
                }

                val savedPath = saveToGallery(resultBitmap)
                statusText.text = "Tamamlandi! Galeriye kaydedildi: $savedPath"
                Toast.makeText(this@MainActivity, "Focus Stack Galeriye Kaydedildi!", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                statusText.text = "Hata: ${e.message}"
            } finally {
                capturedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                isCapturing = false
                btnCapture.isEnabled = true
            }
        }
    }

    private fun rotateBitmap(src: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return src
        val matrix = Matrix().apply { postRotate(angle) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated != src) src.recycle()
        return rotated
    }

    private suspend fun saveToGallery(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val filename = "FocusStack_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/S23Ultra_FocusStack")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        "Pictures/S23Ultra_FocusStack/$filename"
    }

    override fun onResume() {
        super.onResume()
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    override fun onPause() {
        closeCamera()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        super.onPause()
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
}

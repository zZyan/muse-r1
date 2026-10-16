package com.zzyan.r1.muse.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Handler
import android.os.Looper

class CameraActivity : ComponentActivity() {
    private val forMuse get() = intent.getBooleanExtra("for_muse", false)
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var flipButton: Button
    private lateinit var shutter: Button
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var facingFront = false
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private fun run(name: String): Process? = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", name))
    } catch (_: Exception) {
        null
    }

    private fun motor(angle: Int) {
        run("echo $angle > /sys/devices/platform/step_motor_ms35774/orientation")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        previewView = PreviewView(this)
        previewView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(0x88000000.toInt())
        gravityBottom(bar)
        bar.setPadding(0, 40, 0, 40)
        status = TextView(this)
        status.text = "CAMERA"
        status.setTextColor(0xFFFFFFFF.toInt())
        status.gravity = Gravity.CENTER
        bar.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        flipButton = Button(this)
        flipButton.text = "FLIP"
        flipButton.setOnClickListener { flipCamera() }
        shutter = Button(this)
        shutter.text = "CAPTURE"
        shutter.setOnClickListener { takePhoto() }
        buttons.addView(flipButton, rowParams())
        buttons.addView(shutter, rowParams())
        bar.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            status.text = "GRANT CAMERA PERMISSION"
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), PERMISSION_REQUEST)
        }
    }

    private fun gravityBottom(bar: LinearLayout) {
        bar.gravity = Gravity.BOTTOM
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(30, 10, 30, 10)
    }

    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera()
            } catch (e: Exception) {
                status.text = "CAMERA ERROR: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCapture = capture
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            bound = true
            previewView.rotation = if (facingFront) 180f else 0f
            status.text = if (facingFront) "FRONT" else "REAR"
        } catch (e: Exception) {
            bound = false
            status.text = "BIND FAILED: ${e.message}"
        }
    }

    private fun flipCamera() {
        facingFront = !facingFront
        bindCamera()
        handler.postDelayed({ if (!bound) bindCamera() }, 1200)
        val motorAngle = if (facingFront) 0 else 180
        motor(motorAngle)
    }

    internal fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MUSE_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MuseCamera")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    var label = "SAVED ${outputFileResults.savedUri?.lastPathSegment ?: "ok"}"
                    if (facingFront) {
                        try {
                            val uri = outputFileResults.savedUri
                            if (uri != null) {
                                val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                                if (bmp != null) {
                                    val m = android.graphics.Matrix().apply { postRotate(180f) }
                                    val rotated = Bitmap.createBitmap(
                                        bmp, 0, 0, bmp.width, bmp.height, m, true)
                                    bmp.recycle()
                                    contentResolver.openOutputStream(uri, "wt")?.use {
                                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, it)
                                    }
                                    rotated.recycle()
                                    label = "SAVED (flipped)"
                                }
                            }
                        } catch (e: Exception) {
                            label = "SAVED(unflipped): ${e.message}"
                        }
                    }
                    if (forMuse) {
                        val result = android.content.Intent().apply {
                            putExtra("photo_uri", outputFileResults.savedUri?.toString().orEmpty())
                        }
                        setResult(RESULT_OK, result)
                        finish()
                        return
                    }
                    status.text = label
                    Toast.makeText(this@CameraActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exception: ImageCaptureException) {
                    status.text = "ERROR: ${exception.message}"
                }
            })
    }

    override fun onResume() {
        super.onResume()
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.let {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        motor(180)
    }

    override fun onPause() {
        super.onPause()
        motor(90)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            status.text = "PERMISSION DENIED"
        }
    }

    companion object {
        const val PERMISSION_REQUEST = 4102
        var instance: CameraActivity? = null
            private set
    }

    override fun onStart() {
        super.onStart()
        instance = this
    }

    override fun onStop() {
        super.onStop()
        if (instance === this) instance = null
    }
}
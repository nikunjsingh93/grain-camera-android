package com.graincamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import android.view.Surface
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale
import com.graincamera.R
import com.graincamera.AspectRatioGLSurfaceView
import com.graincamera.gl.FilmSim
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.graincamera.util.MediaStoreSaver
import android.view.View
import android.graphics.Bitmap

class MainActivity : ComponentActivity() {
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var imageCapture: ImageCapture

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
        glView.setZOrderOnTop(false)


        setupUI(glView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setupUI(glView: AspectRatioGLSurfaceView) {
        val renderer = glView.renderer

        val spinner = findViewById<Spinner>(R.id.filmSpinner)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            FilmSim.values().map { it.displayName }
        )
        spinner.setSelection(FilmSim.PROVIA.ordinal)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                renderer.params = renderer.params.copy(film = FilmSim.values()[position].film)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }

        findViewById<SeekBar>(R.id.halationSeek).setOnSeekBarChangeListener(SimpleSeek { v ->
            renderer.params = renderer.params.copy(halation = v)
        })
        findViewById<SeekBar>(R.id.bloomSeek).setOnSeekBarChangeListener(SimpleSeek { v ->
            renderer.params = renderer.params.copy(bloom = v)
        })
        findViewById<SeekBar>(R.id.grainSeek).setOnSeekBarChangeListener(SimpleSeek { v ->
            renderer.params = renderer.params.copy(grain = v)
        })

        findViewById<Button>(R.id.switchBtn).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }

        findViewById<Button>(R.id.captureBtn).setOnClickListener {
            takePhoto()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
            val renderer = glView.renderer
            val preview = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            preview.setSurfaceProvider { request ->
                val surface = renderer.provideSurface(request.resolution)
                request.provideSurface(surface, cameraExecutor) { }
            }

            // Create ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to bind camera: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun captureViewAsBitmap(view: View): Bitmap {
        // Create a bitmap with the view's dimensions
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        
        // Create a canvas and draw the view into it
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        
        return bitmap
    }
    
    private fun takePhoto() {
        if (!::imageCapture.isInitialized) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCamera")
            }
        }
        
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        Toast.makeText(this, getString(R.string.saving), Toast.LENGTH_SHORT).show()

        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Photo saved successfully!", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    
    private fun captureScreenAsBitmap(): Bitmap {
        // Capture the entire screen using PixelCopy (Android 8.0+)
        val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
        val bitmap = Bitmap.createBitmap(glView.width, glView.height, Bitmap.Config.ARGB_8888)
        
        // Use a simple approach - capture the view's current state
        val canvas = android.graphics.Canvas(bitmap)
        glView.draw(canvas)
        
        return bitmap
    }
    

}

private class SimpleSeek(val on: (Float)->Unit): SeekBar.OnSeekBarChangeListener {
    private fun map(p: Int): Float = p/100f
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { on(map(progress)) }
    override fun onStartTrackingTouch(seekBar: SeekBar?) { }
    override fun onStopTrackingTouch(seekBar: SeekBar?) { }
}

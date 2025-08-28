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
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.graincamera.util.MediaStoreSaver
import android.view.View
import android.graphics.Bitmap
import android.graphics.BitmapFactory

class MainActivity : ComponentActivity() {
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

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
    
    override fun onResume() {
        super.onResume()
        if (imageCapture == null) startCamera()
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
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
            val renderer = glView.renderer
            val preview = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            preview.setSurfaceProvider { request ->
                val surface = renderer.provideSurface(request.resolution)
                request.provideSurface(surface, cameraExecutor) { }
            }

            // Use the current camera selector
            val selector = cameraSelector

            // IMPORTANT: assign to the field, not a local val
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, selector, preview, imageCapture
                )
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
        val ic = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, getString(R.string.saving), Toast.LENGTH_SHORT).show()

        // Use ImageCapture with a callback to process the captured image
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    try {
                        // Convert ImageProxy to Bitmap
                        val bitmap = imageProxyToBitmap(image)
                        
                        // Apply the current filter effects to the bitmap
                        val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
                        val renderer = glView.renderer
                        val filteredBitmap = applyFilterToBitmap(bitmap, renderer)
                        
                        // Save the filtered bitmap
                        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            if (Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCamera")
                            }
                        }
                        
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let { imageUri ->
                            contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                                filteredBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                            }
                            Toast.makeText(this@MainActivity, "Photo saved successfully!", Toast.LENGTH_SHORT).show()
                        } ?: run {
                            Toast.makeText(this@MainActivity, "Failed to save photo", Toast.LENGTH_LONG).show()
                        }
                        
                        // Clean up
                        bitmap.recycle()
                        filteredBitmap.recycle()
                        
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "Failed to process photo: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        image.close()
                    }
                }
                
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    
    private fun imageProxyToBitmap(image: androidx.camera.core.ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        
        // Fix rotation based on image info
        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
    
    private fun applyFilterToBitmap(bitmap: Bitmap, renderer: com.graincamera.gl.GLRenderer): Bitmap {
        try {
            // Try to capture the actual rendered frame from OpenGL
            android.util.Log.d("MainActivity", "Attempting OpenGL capture for filtered image")
            val filteredBitmap = renderer.captureCurrentFrame()
            android.util.Log.d("MainActivity", "OpenGL capture successful: ${filteredBitmap.width}x${filteredBitmap.height}")
            return filteredBitmap
        } catch (e: Exception) {
            // Fallback to basic filter application if OpenGL capture fails
            android.util.Log.w("MainActivity", "OpenGL capture failed, using fallback: ${e.message}")
            val params = renderer.params
            val film = params.film
            
            // Create a mutable copy of the bitmap
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(mutableBitmap)
            
            // Apply film simulation effects
            val paint = android.graphics.Paint().apply {
                // Apply contrast
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply {
                        setSaturation(film.saturation)
                    }
                )
            }
            
            // Apply the paint to the canvas
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            
            // For Acros B&W, apply additional black and white conversion
            if (film.saturation == 0.0f) {
                val bwPaint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply {
                            setSaturation(0f) // Make it black and white
                        }
                    )
                }
                canvas.drawBitmap(mutableBitmap, 0f, 0f, bwPaint)
            }
            
            return mutableBitmap
        }
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


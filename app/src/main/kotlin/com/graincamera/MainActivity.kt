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
import kotlin.math.exp

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
        val params = renderer.params
        val film = params.film
        
        // Create a mutable copy of the bitmap
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)
        
        // Apply film simulation effects with enhanced processing
        val paint = android.graphics.Paint().apply {
            // Apply saturation and basic color adjustments
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
        
        // Apply realistic halation and bloom effects
        if (params.halation > 0.0f || params.bloom > 0.0f) {
            applyHalationAndBloom(mutableBitmap, params.halation, params.bloom)
        }
        
        // Apply grain effect
        if (params.grain > 0.0f) {
            applyGrainEffect(mutableBitmap, params.grain)
        }
        
        return mutableBitmap
    }
    
    private fun applyGrainEffect(bitmap: Bitmap, grainIntensity: Float) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val random = java.util.Random()
        val grainAmount = (grainIntensity * 50).toInt()
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            
            val noise = random.nextInt(grainAmount * 2 + 1) - grainAmount
            
            val newR = (r + noise).coerceIn(0, 255)
            val newG = (g + noise).coerceIn(0, 255)
            val newB = (b + noise).coerceIn(0, 255)
            
            pixels[i] = android.graphics.Color.rgb(newR, newG, newB)
        }
        
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    
    private fun applyHalationAndBloom(bitmap: Bitmap, halationIntensity: Float, bloomIntensity: Float) {
        try {
            // Scale down for performance if image is too large
            val scaleFactor = if (bitmap.width * bitmap.height > 2000000) 0.5f else 1.0f
            val workingBitmap = if (scaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(bitmap, 
                    (bitmap.width * scaleFactor).toInt(), 
                    (bitmap.height * scaleFactor).toInt(), true)
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
            
            val width = workingBitmap.width
            val height = workingBitmap.height
            val pixels = IntArray(width * height)
            workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Create a bright-pass mask for halation and bloom
            val halationPixels = IntArray(width * height)
            val bloomPixels = IntArray(width * height)
            
            // Calculate brightness and create masks (optimized)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                // Calculate luminance (same as in shader)
                val luminance = r * 0.2126f + g * 0.7152f + b * 0.0722f
                
                // Bright-pass threshold (similar to shader's smoothstep(0.85, 1.0, Y))
                val bright = if (luminance > 217) { // 0.85 * 255
                    (luminance - 217) / (255 - 217) // Normalize to 0-1
                } else {
                    0f
                }.coerceIn(0f, 1f)
                
                // Create halation effect: red-biased halo
                val halationR = (bright * halationIntensity * 255).toInt().coerceIn(0, 255)
                val halationG = (bright * halationIntensity * 255 * 0.3f).toInt().coerceIn(0, 255)
                val halationB = (bright * halationIntensity * 255 * 0.2f).toInt().coerceIn(0, 255)
                halationPixels[i] = android.graphics.Color.rgb(halationR, halationG, halationB)
                
                // Create bloom effect: white glow
                val bloomValue = (bright * bloomIntensity * 255).toInt().coerceIn(0, 255)
                bloomPixels[i] = android.graphics.Color.rgb(bloomValue, bloomValue, bloomValue)
            }
            
            // Apply optimized blur to halation and bloom
            val blurredHalation = applyFastBlur(halationPixels, width, height, (4f + 8f * halationIntensity).toInt())
            val blurredBloom = applyFastBlur(bloomPixels, width, height, (2f + 4f * bloomIntensity).toInt())
            
            // Combine effects with original image
            for (i in pixels.indices) {
                val originalPixel = pixels[i]
                val halationPixel = blurredHalation[i]
                val bloomPixel = blurredBloom[i]
                
                val originalR = android.graphics.Color.red(originalPixel)
                val originalG = android.graphics.Color.green(originalPixel)
                val originalB = android.graphics.Color.blue(originalPixel)
                
                val halationR = android.graphics.Color.red(halationPixel)
                val halationG = android.graphics.Color.green(halationPixel)
                val halationB = android.graphics.Color.blue(halationPixel)
                
                val bloomR = android.graphics.Color.red(bloomPixel)
                val bloomG = android.graphics.Color.green(bloomPixel)
                val bloomB = android.graphics.Color.blue(bloomPixel)
                
                // Add halation and bloom to original
                val finalR = (originalR + halationR + bloomR).coerceIn(0, 255)
                val finalG = (originalG + halationG + bloomG).coerceIn(0, 255)
                val finalB = (originalB + halationB + bloomB).coerceIn(0, 255)
                
                pixels[i] = android.graphics.Color.rgb(finalR, finalG, finalB)
            }
            
            workingBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            // Scale back up if needed
            if (scaleFactor < 1.0f) {
                val finalBitmap = Bitmap.createScaledBitmap(workingBitmap, bitmap.width, bitmap.height, true)
                workingBitmap.recycle()
                val finalPixels = IntArray(bitmap.width * bitmap.height)
                finalBitmap.getPixels(finalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.setPixels(finalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                finalBitmap.recycle()
            } else {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            
        } catch (e: Exception) {
            // Fallback to simple effect if processing fails
            android.util.Log.w("MainActivity", "Halation processing failed: ${e.message}")
            applySimpleHalation(bitmap, halationIntensity, bloomIntensity)
        }
    }
    
    private fun applyFastBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels
        
        val result = IntArray(pixels.size)
        val kernelSize = radius * 2 + 1
        
        // Apply horizontal blur
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                
                for (i in -radius..radius) {
                    val sampleX = (x + i).coerceIn(0, width - 1)
                    val pixel = pixels[y * width + sampleX]
                    r += android.graphics.Color.red(pixel)
                    g += android.graphics.Color.green(pixel)
                    b += android.graphics.Color.blue(pixel)
                    count++
                }
                
                result[y * width + x] = android.graphics.Color.rgb(
                    r / count,
                    g / count,
                    b / count
                )
            }
        }
        
        // Apply vertical blur
        val finalResult = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                
                for (i in -radius..radius) {
                    val sampleY = (y + i).coerceIn(0, height - 1)
                    val pixel = result[sampleY * width + x]
                    r += android.graphics.Color.red(pixel)
                    g += android.graphics.Color.green(pixel)
                    b += android.graphics.Color.blue(pixel)
                    count++
                }
                
                finalResult[y * width + x] = android.graphics.Color.rgb(
                    r / count,
                    g / count,
                    b / count
                )
            }
        }
        
        return finalResult
    }
    
    private fun applySimpleHalation(bitmap: Bitmap, halationIntensity: Float, bloomIntensity: Float) {
        val canvas = android.graphics.Canvas(bitmap)
        
        // Simple blur effect for halation
        if (halationIntensity > 0.0f) {
            val halationPaint = android.graphics.Paint().apply {
                maskFilter = android.graphics.BlurMaskFilter(
                    halationIntensity * 15f, 
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply {
                        setScale(1.2f, 0.8f, 0.7f, 1.0f) // Red bias
                    }
                )
                alpha = (halationIntensity * 128).toInt()
            }
            canvas.drawBitmap(bitmap, 0f, 0f, halationPaint)
        }
        
        // Simple blur effect for bloom
        if (bloomIntensity > 0.0f) {
            val bloomPaint = android.graphics.Paint().apply {
                maskFilter = android.graphics.BlurMaskFilter(
                    bloomIntensity * 10f, 
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
                alpha = (bloomIntensity * 100).toInt()
            }
            canvas.drawBitmap(bitmap, 0f, 0f, bloomPaint)
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


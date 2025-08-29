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
import com.graincamera.FilmSettingsStore
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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.Data
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import com.graincamera.gl.EffectParams
 

class MainActivity : ComponentActivity() {
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var camera: androidx.camera.core.Camera? = null
    
    
    // Background processing
    private lateinit var notificationManager: NotificationManager
    private lateinit var workManager: WorkManager
    private val processingCounter = AtomicInteger(0)
    private val CHANNEL_ID = "photo_processing"
    private val NOTIFICATION_ID = 1001

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize background processing
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        workManager = WorkManager.getInstance(applicationContext)
        createNotificationChannel()

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
        // Always re-apply film and settings on resume
        startCamera()
        // Update film name display
        updateFilmNameDisplay()

		// Enable orientation listener to align saved image orientation with device
		if (orientationEventListener == null) {
			orientationEventListener = object : android.view.OrientationEventListener(this) {
				override fun onOrientationChanged(orientation: Int) {
					if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
					val rotation = when (orientation) {
						in 45..134 -> Surface.ROTATION_270
						in 135..224 -> Surface.ROTATION_180
						in 225..314 -> Surface.ROTATION_90
						else -> Surface.ROTATION_0
					}
					imageCapture?.targetRotation = rotation
				}
			}
		}
		orientationEventListener?.enable()
    }

	override fun onPause() {
		super.onPause()
		orientationEventListener?.disable()
	}

    private fun setupUI(glView: AspectRatioGLSurfaceView) {
        val renderer = glView.renderer

        findViewById<View>(R.id.captureCircle).setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.filmBtn).setOnClickListener {
            startActivity(Intent(this, FilmSelectionActivity::class.java))
        }

        findViewById<ImageButton>(R.id.ruleOfThirdsBtn).setOnClickListener {
            val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
            val renderer = glView.renderer
            val currentParams = renderer.params
            val newShowRuleOfThirds = !currentParams.showRuleOfThirds
            renderer.params = currentParams.copy(showRuleOfThirds = newShowRuleOfThirds)
            
            // Update button state
            findViewById<ImageButton>(R.id.ruleOfThirdsBtn).isSelected = newShowRuleOfThirds
        }

        findViewById<View>(R.id.switchBtn).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }

        // Update film name display
        updateFilmNameDisplay()
        
        // Initialize rule of thirds button state
        findViewById<ImageButton>(R.id.ruleOfThirdsBtn).isSelected = renderer.params.showRuleOfThirds

		// Tap-to-focus removed
    }

    

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
            val renderer = glView.renderer
            // Apply persisted film and settings
            val selectedFilmName = FilmSettingsStore.getSelectedFilm(this)
            val selectedFilm = com.graincamera.gl.FilmSim.values().firstOrNull { it.name == selectedFilmName } ?: com.graincamera.gl.FilmSim.PROVIA
            renderer.params = renderer.params.copy(film = selectedFilm.film)
            FilmSettingsStore.getSettingsForFilm(this, selectedFilm.name).let { s ->
                renderer.params = renderer.params.copy(halation = s.halation, bloom = s.bloom, grain = s.grain)
            }
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
                camera = cameraProvider.bindToLifecycle(
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

        // Show immediate feedback
        Toast.makeText(this, "Photo captured! Processing in background...", Toast.LENGTH_SHORT).show()
        showProcessingNotification()

        // Use ImageCapture with a callback to queue the image for background processing
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    try {
                        // Convert ImageProxy to Bitmap
                        val bitmap = imageProxyToBitmap(image)
                        
                        // Queue for background processing
                        queuePhotoForProcessing(bitmap)
                        
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "Failed to capture photo: ${e.message}", Toast.LENGTH_LONG).show()
                        hideProcessingNotification()
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                    hideProcessingNotification()
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

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val width = mutableBitmap.width
        val height = mutableBitmap.height
        val pixels = IntArray(width * height)
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val exposureMul = Math.pow(2.0, params.exposure.toDouble()).toFloat()
        val contrast = film.contrast
        val saturation = film.saturation
        val shadowTint = film.shadowTint
        val highlightTint = film.highlightTint

        // Precompute tint colors like the shader
        fun mix(a: Float, b: Float, t: Float) = a * (1f - t) + b * t
        val shadowWarm = floatArrayOf(1.0f, 0.95f, 0.9f)
        val shadowCool = floatArrayOf(0.9f, 0.95f, 1.05f)
        val highlightWarm = floatArrayOf(1.05f, 1.0f, 0.95f)
        val highlightCool = floatArrayOf(0.95f, 1.0f, 1.05f)

        val shadowColor = floatArrayOf(
            mix(1.0f, if (shadowTint >= 0f) shadowWarm[0] else shadowCool[0], kotlin.math.abs(shadowTint)),
            mix(1.0f, if (shadowTint >= 0f) shadowWarm[1] else shadowCool[1], kotlin.math.abs(shadowTint)),
            mix(1.0f, if (shadowTint >= 0f) shadowWarm[2] else shadowCool[2], kotlin.math.abs(shadowTint))
        )
        val highlightColor = floatArrayOf(
            mix(1.0f, if (highlightTint >= 0f) highlightWarm[0] else highlightCool[0], kotlin.math.abs(highlightTint)),
            mix(1.0f, if (highlightTint >= 0f) highlightWarm[1] else highlightCool[1], kotlin.math.abs(highlightTint)),
            mix(1.0f, if (highlightTint >= 0f) highlightWarm[2] else highlightCool[2], kotlin.math.abs(highlightTint))
        )

        fun luma(r: Float, g: Float, b: Float): Float = (0.2126f * r + 0.7152f * g + 0.0722f * b)
        fun clamp01(v: Float) = v.coerceIn(0f, 1f)
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = clamp01(((x - edge0) / (edge1 - edge0)))
            return t * t * (3f - 2f * t)
        }

        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (android.graphics.Color.red(c) / 255f)
            var g = (android.graphics.Color.green(c) / 255f)
            var b = (android.graphics.Color.blue(c) / 255f)

            // ToneMap: exposure in stops and contrast around 0.5
            r = clamp01(((r * exposureMul) - 0.5f) * contrast + 0.5f)
            g = clamp01(((g * exposureMul) - 0.5f) * contrast + 0.5f)
            b = clamp01(((b * exposureMul) - 0.5f) * contrast + 0.5f)

            // Saturation via luma mix
            val Y = luma(r, g, b)
            r = mix(Y, r, saturation)
            g = mix(Y, g, saturation)
            b = mix(Y, b, saturation)

            // Split tone blend between shadow and highlight tints
            val y2 = luma(r, g, b)
            val t = smoothstep(0.35f, 0.65f, y2)
            val rs = r * shadowColor[0]
            val gs = g * shadowColor[1]
            val bs = b * shadowColor[2]
            val rh = r * highlightColor[0]
            val gh = g * highlightColor[1]
            val bh = b * highlightColor[2]
            r = mix(rs, rh, t)
            g = mix(gs, gh, t)
            b = mix(bs, bh, t)

            pixels[i] = android.graphics.Color.rgb(
                (clamp01(r) * 255f + 0.5f).toInt(),
                (clamp01(g) * 255f + 0.5f).toInt(),
                (clamp01(b) * 255f + 0.5f).toInt()
            )
        }

        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        if (params.halation > 0.0f || params.bloom > 0.0f) {
            applyHalationAndBloom(mutableBitmap, params.halation, params.bloom)
        }
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

    // Background processing methods
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of photo processing"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showProcessingNotification() {
        val count = processingCounter.incrementAndGet()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Processing Photos")
            .setContentText("$count photo${if (count > 1) "s" else ""} in queue")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideProcessingNotification() {
        val count = processingCounter.decrementAndGet()
        if (count <= 0) {
            notificationManager.cancel(NOTIFICATION_ID)
        } else {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Processing Photos")
                .setContentText("$count photo${if (count > 1) "s" else ""} remaining")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun queuePhotoForProcessing(bitmap: Bitmap) {
        try {
            // Get current filter parameters
            val glView: AspectRatioGLSurfaceView = findViewById(R.id.glView)
            val renderer = glView.renderer
            val params = renderer.params
            
            // Save bitmap to temporary file
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
            val tempFile = File(cacheDir, "temp_photo_$timestamp.jpg")
            
            tempFile.outputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
            
            // Create work data
            val inputData = Data.Builder()
                .putString("timestamp", timestamp)
                .putString("tempFilePath", tempFile.absolutePath)
                .putFloat("halation", params.halation)
                .putFloat("bloom", params.bloom)
                .putFloat("grain", params.grain)
                .putFloat("saturation", params.film.saturation)
                .putFloat("exposure", params.exposure)
                .putString("filmName", com.graincamera.gl.FilmSim.values().firstOrNull { it.film == params.film }?.name
                    ?: com.graincamera.gl.FilmSim.PROVIA.name)
                .build()
            
            // Create and enqueue the work
            val photoProcessingWork: WorkRequest = OneTimeWorkRequestBuilder<PhotoProcessingWorker>()
                .setInputData(inputData)
                .build()
            
            workManager.enqueue(photoProcessingWork)
            
            // Clean up the original bitmap
            bitmap.recycle()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error queuing photo for processing", e)
            bitmap.recycle()
            hideProcessingNotification()
        }
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
    
    private fun updateFilmNameDisplay() {
        val currentFilmName = FilmSettingsStore.getSelectedFilm(this)
        val film = com.graincamera.gl.FilmSim.values().firstOrNull { it.name == currentFilmName }
        findViewById<TextView>(R.id.currentFilmName).text = film?.displayName ?: "ProView Neutral"
    }

}

private class SimpleSeek(val on: (Float)->Unit): SeekBar.OnSeekBarChangeListener {
    private fun map(p: Int): Float = p/100f
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { on(map(progress)) }
    override fun onStartTrackingTouch(seekBar: SeekBar?) { }
    override fun onStopTrackingTouch(seekBar: SeekBar?) { }
}

class SimpleSeekPublic(val on: ()->Unit): SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { on() }
    override fun onStartTrackingTouch(seekBar: SeekBar?) { }
    override fun onStopTrackingTouch(seekBar: SeekBar?) { }
}


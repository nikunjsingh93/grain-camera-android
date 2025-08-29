package com.graincamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.graincamera.gl.FilmSim
import com.graincamera.gl.EffectParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.FileOutputStream

class PhotoProcessingWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "photo_processing_fg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Photo Processing", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Processing photo")
            .setContentText("Saving to gallery…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(2001, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var tempFilePath: String? = null
        try {
            // Promote to foreground to survive background restrictions (Android 13)
            setForeground(getForegroundInfo())

            // Get input data
            val timestamp = inputData.getString("timestamp") ?: return@withContext Result.failure()
            tempFilePath = inputData.getString("tempFilePath") ?: return@withContext Result.failure()
            val halation = inputData.getFloat("halation", 0f)
            val bloom = inputData.getFloat("bloom", 0f)
            val grain = inputData.getFloat("grain", 0f)
            val grainSize = inputData.getFloat("grainSize", 1.5f)
            val grainRoughness = inputData.getFloat("grainRoughness", 0.5f)
            val saturation = inputData.getFloat("saturation", 1f)
            val filmName = inputData.getString("filmName") ?: "PROVIA"
            val exposure = inputData.getFloat("exposure", 0f)
            val contrast = inputData.getFloat("contrast", 1.0f)
            
            // Create params object
            val filmBase = FilmSim.values().find { it.name == filmName }?.film ?: FilmSim.PROVIA.film
            val params = EffectParams(
                film = filmBase.copy(saturation = saturation, contrast = contrast),
                halation = halation,
                bloom = bloom,
                grain = grain,
                grainSize = grainSize,
                grainRoughness = grainRoughness,
                exposure = exposure
            )
            
            Log.d("PhotoProcessingWorker", "Processing photo from $tempFilePath with params: halation=$halation, bloom=$bloom, grain=$grain, grainSize=$grainSize, grainRoughness=$grainRoughness")
            
            // Load bitmap from temp file
            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                Log.e("PhotoProcessingWorker", "Temp file not found: $tempFilePath")
                return@withContext Result.failure()
            }
            
            val originalBitmap = BitmapFactory.decodeFile(tempFilePath)
            if (originalBitmap == null) {
                Log.e("PhotoProcessingWorker", "Failed to decode bitmap from: $tempFilePath")
                return@withContext Result.failure()
            }
            
            // Apply filters and attempt save; fall back to original on failure
            val processedBitmap = try {
                applyFiltersToBitmap(originalBitmap, params)
            } catch (e: Exception) {
                Log.e("PhotoProcessingWorker", "Processing failed, saving original: ${e.message}")
                originalBitmap
            }

            val savedUri = saveBitmapToGallery(processedBitmap, timestamp)

            // Clean up
            if (processedBitmap !== originalBitmap) processedBitmap.recycle()
            originalBitmap.recycle()
            
            // Delete temp file
            tempFile.delete()
            
            Result.success(androidx.work.Data.Builder().putString("savedUri", savedUri).build())
        } catch (e: Exception) {
            Log.e("PhotoProcessingWorker", "Error processing photo", e)
            // Try to clean up temp file even on error
            tempFilePath?.let { path ->
                try {
                    val tempFile = File(path)
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                } catch (cleanupError: Exception) {
                    Log.e("PhotoProcessingWorker", "Error cleaning up temp file", cleanupError)
                }
            }
            Result.failure()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, timestamp: String): String {
        val name = "GrainCamera_$timestamp.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            requireNotNull(uri) { "Insert into MediaStore returned null URI" }
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
            val cv = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, cv, null, null)
            Log.d("PhotoProcessingWorker", "Photo saved: $uri")
            return uri.toString()
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val outDir = File(picturesDir, "GrainCamera")
            if (!outDir.exists()) outDir.mkdirs()
            val outFile = File(outDir, name)
            FileOutputStream(outFile).use { fos: FileOutputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/jpeg"), null)
            Log.d("PhotoProcessingWorker", "Photo saved (legacy): ${outFile.absolutePath}")
            return Uri.fromFile(outFile).toString()
        }
    }
    
    private fun applyFiltersToBitmap(bitmap: Bitmap, params: EffectParams): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val width = mutableBitmap.width
        val height = mutableBitmap.height
        val pixels = IntArray(width * height)
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val exposureMul = Math.pow(2.0, params.exposure.toDouble()).toFloat()
        val contrast = params.film.contrast
        val saturation = params.film.saturation
        val shadowTint = params.film.shadowTint
        val highlightTint = params.film.highlightTint

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

            // ToneMap: exposure and contrast
            r = clamp01(((r * exposureMul) - 0.5f) * contrast + 0.5f)
            g = clamp01(((g * exposureMul) - 0.5f) * contrast + 0.5f)
            b = clamp01(((b * exposureMul) - 0.5f) * contrast + 0.5f)

            // Saturation via luma mix
            val Y = luma(r, g, b)
            r = mix(Y, r, saturation)
            g = mix(Y, g, saturation)
            b = mix(Y, b, saturation)

            // Split tone blend
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
            applyGrainEffect(mutableBitmap, params.grain, params.grainSize, params.grainRoughness)
        }

        return mutableBitmap
    }
    
    private fun applyHalationAndBloom(bitmap: Bitmap, halationIntensity: Float, bloomIntensity: Float) {
        // Scale down for performance on very large images
        val scaleFactor = if (bitmap.width * bitmap.height > 2000000) 0.5f else 1.0f
        val workingBitmap = if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scaleFactor).toInt(),
                (bitmap.height * scaleFactor).toInt(),
                true)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val width = workingBitmap.width
        val height = workingBitmap.height
        val pixels = IntArray(width * height)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val mask = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = android.graphics.Color.red(p)
            val g = android.graphics.Color.green(p)
            val b = android.graphics.Color.blue(p)
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val t = if (y > 204f) ((y - 204f) / (255f - 204f)).coerceIn(0f, 1f) else 0f
            mask[i] = (t * 255f).toInt()
        }

        // Create red-biased halation buffer
        val hal = IntArray(pixels.size)
        for (i in pixels.indices) {
            val v = mask[i]
            hal[i] = android.graphics.Color.rgb((v * halationIntensity).toInt().coerceIn(0,255), (v * halationIntensity * 0.3f).toInt().coerceIn(0,255), (v * halationIntensity * 0.2f).toInt().coerceIn(0,255))
        }

        // Soften edges with larger blur for halation
        val halBlur = stackBlur(hal, width, height, (6f + 10f * halationIntensity).toInt().coerceAtLeast(1))

        // Bloom buffer
        val blm = IntArray(pixels.size)
        for (i in pixels.indices) {
            val v = mask[i]
            blm[i] = android.graphics.Color.rgb((v * bloomIntensity).toInt().coerceIn(0,255), (v * bloomIntensity).toInt().coerceIn(0,255), (v * bloomIntensity).toInt().coerceIn(0,255))
        }
        val blmBlur = stackBlur(blm, width, height, (2f + 6f * bloomIntensity).toInt().coerceAtLeast(1))

        for (i in pixels.indices) {
            val o = pixels[i]
            val r = (android.graphics.Color.red(o) + android.graphics.Color.red(halBlur[i]) + android.graphics.Color.red(blmBlur[i])).coerceIn(0,255)
            val g = (android.graphics.Color.green(o) + android.graphics.Color.green(halBlur[i]) + android.graphics.Color.green(blmBlur[i])).coerceIn(0,255)
            val b = (android.graphics.Color.blue(o) + android.graphics.Color.blue(halBlur[i]) + android.graphics.Color.blue(blmBlur[i])).coerceIn(0,255)
            pixels[i] = android.graphics.Color.rgb(r,g,b)
        }

        workingBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // If we scaled down, scale back up onto the original bitmap
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
    }
    
    private fun applyGrainEffect(bitmap: Bitmap, grainIntensity: Float, grainSize: Float, grainRoughness: Float) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val w = bitmap.width
        val h = bitmap.height
        val block = max(1, grainSize.roundToInt())
        fun hash(x: Int, y: Int): Int {
            var n = x * 374761393 + y * 668265263
            n = (n xor (n shr 13)) * 1274126177
            return n xor (n shr 16)
        }
        fun valueNoise(ix: Int, iy: Int): Float = ((hash(ix, iy) and 0x7fffffff) / 2147483647.0f)
        fun fbm(ix: Int, iy: Int): Float {
            var sum = 0f
            var amp = 0.6f
            var sx = ix
            var sy = iy
            val decay = 0.45f + 0.30f * grainRoughness.coerceIn(0f, 1f)
            repeat(4) {
                sum += valueNoise(sx, sy) * amp
                sx *= 2; sy *= 2
                amp *= decay
            }
            return sum
        }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val n = fbm(x / block, y / block) - 0.5f
                val noise = (n * (grainIntensity * 90f)).toInt()
                val xMax = min(w, x + block)
                val yMax = min(h, y + block)
                var yy = y
                while (yy < yMax) {
                    var xx = x
                    while (xx < xMax) {
                        val idx = yy * w + xx
                        val p = pixels[idx]
                        val r = (android.graphics.Color.red(p) + noise).coerceIn(0,255)
                        val g = (android.graphics.Color.green(p) + noise).coerceIn(0,255)
                        val b = (android.graphics.Color.blue(p) + noise).coerceIn(0,255)
                        pixels[idx] = android.graphics.Color.rgb(r,g,b)
                        xx++
                    }
                    yy++
                }
                x += block
            }
            y += block
        }
        
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun stackBlur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return src
        val rs = src.copyOf()
        // Horizontal pass
        for (y in 0 until height) {
            var sumR = 0; var sumG = 0; var sumB = 0
            var count = 0
            for (x in -radius..radius) {
                val xx = x.coerceIn(0, width - 1)
                val p = rs[y * width + xx]
                sumR += android.graphics.Color.red(p)
                sumG += android.graphics.Color.green(p)
                sumB += android.graphics.Color.blue(p)
                count++
            }
            for (x in 0 until width) {
                val idx = y * width + x
                val r = sumR / count; val g = sumG / count; val b = sumB / count
                rs[idx] = android.graphics.Color.rgb(r, g, b)
                val xOut = (x - radius).coerceIn(0, width - 1)
                val pOut = src[y * width + xOut]
                sumR -= android.graphics.Color.red(pOut)
                sumG -= android.graphics.Color.green(pOut)
                sumB -= android.graphics.Color.blue(pOut)
                val xIn = (x + radius + 1).coerceIn(0, width - 1)
                val pIn = src[y * width + xIn]
                sumR += android.graphics.Color.red(pIn)
                sumG += android.graphics.Color.green(pIn)
                sumB += android.graphics.Color.blue(pIn)
            }
        }
        // Vertical pass
        val out = IntArray(src.size)
        for (x in 0 until width) {
            var sumR = 0; var sumG = 0; var sumB = 0
            var count = 0
            for (y in -radius..radius) {
                val yy = y.coerceIn(0, height - 1)
                val p = rs[yy * width + x]
                sumR += android.graphics.Color.red(p)
                sumG += android.graphics.Color.green(p)
                sumB += android.graphics.Color.blue(p)
                count++
            }
            for (y in 0 until height) {
                val idx = y * width + x
                val r = sumR / count; val g = sumG / count; val b = sumB / count
                out[idx] = android.graphics.Color.rgb(r, g, b)
                val yOut = (y - radius).coerceIn(0, height - 1)
                val pOut = rs[yOut * width + x]
                sumR -= android.graphics.Color.red(pOut)
                sumG -= android.graphics.Color.green(pOut)
                sumB -= android.graphics.Color.blue(pOut)
                val yIn = (y + radius + 1).coerceIn(0, height - 1)
                val pIn = rs[yIn * width + x]
                sumR += android.graphics.Color.red(pIn)
                sumG += android.graphics.Color.green(pIn)
                sumB += android.graphics.Color.blue(pIn)
            }
        }
        return out
    }
}

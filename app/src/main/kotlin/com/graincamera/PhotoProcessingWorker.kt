package com.graincamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.graincamera.gl.FilmSim
import com.graincamera.gl.EffectParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoProcessingWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var tempFilePath: String? = null
        try {
            // Get input data
            val timestamp = inputData.getString("timestamp") ?: return@withContext Result.failure()
            tempFilePath = inputData.getString("tempFilePath") ?: return@withContext Result.failure()
            val halation = inputData.getFloat("halation", 0f)
            val bloom = inputData.getFloat("bloom", 0f)
            val grain = inputData.getFloat("grain", 0f)
            val saturation = inputData.getFloat("saturation", 1f)
            val filmName = inputData.getString("filmName") ?: "PROVIA"
            val exposure = inputData.getFloat("exposure", 0f)
            
            // Create params object
            val film = FilmSim.values().find { it.name == filmName }?.film ?: FilmSim.PROVIA.film
            val params = EffectParams(
                film = film.copy(saturation = saturation),
                halation = halation,
                bloom = bloom,
                grain = grain,
                exposure = exposure
            )
            
            Log.d("PhotoProcessingWorker", "Processing photo from $tempFilePath with params: halation=$halation, bloom=$bloom, grain=$grain")
            
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
            
            // Apply filters
            val processedBitmap = applyFiltersToBitmap(originalBitmap, params)
            
            // Save the processed bitmap
            val name = "GrainCamera_$timestamp"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCamera")
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { imageUri ->
                context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                Log.d("PhotoProcessingWorker", "Photo saved successfully: $imageUri")
            } ?: run {
                Log.e("PhotoProcessingWorker", "Failed to save photo")
                return@withContext Result.failure()
            }

            // Clean up
            originalBitmap.recycle()
            processedBitmap.recycle()
            
            // Delete temp file
            tempFile.delete()
            
            Result.success()
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
            applyGrainEffect(mutableBitmap, params.grain)
        }

        return mutableBitmap
    }
    
    private fun applyHalationAndBloom(bitmap: Bitmap, halationIntensity: Float, bloomIntensity: Float) {
        // Simplified halation/bloom implementation for background processing
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Apply simple bright-pass filter for halation
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            
            val brightness = (r + g + b) / 3f
            if (brightness > 200) { // Bright areas
                val halationR = (r + (halationIntensity * 50)).toInt().coerceIn(0, 255)
                val halationG = (g + (halationIntensity * 20)).toInt().coerceIn(0, 255)
                val halationB = (b + (halationIntensity * 10)).toInt().coerceIn(0, 255)
                pixels[i] = android.graphics.Color.rgb(halationR, halationG, halationB)
            }
        }
        
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    
    private fun applyGrainEffect(bitmap: Bitmap, grainIntensity: Float) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val random = Random()
        val grainAmount = (grainIntensity * 30).toInt()
        
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
}

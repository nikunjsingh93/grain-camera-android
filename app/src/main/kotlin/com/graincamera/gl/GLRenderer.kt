package com.graincamera.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.egl.EGLConfig
import android.util.Size
import android.view.Surface
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import kotlin.concurrent.thread

class GLRenderer(private val context: Context) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private var program = 0
    private var textureOES = 0
    private var surfaceTex: SurfaceTexture? = null
    private var surface: Surface? = null
    private var uTexMatrix = 0
    private var uResolution = 0
    private var uTime = 0
    private var uParams = 0
    private var uFilm = 0
    private var uShowRuleOfThirds = 0
    private var uGrainSizeLoc = 0
    private var uGrainRoughnessLoc = 0

    private val stMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        ))
        position(0)
    }
    private val tex: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        ))
        position(0)
    }

    private var viewW = 1
    private var viewH = 1
    private var timeStart = System.nanoTime()

    @Volatile var params = EffectParams()
    private val frameAvailable = AtomicBoolean(false)

    fun setViewport(w: Int, h: Int) {
        viewW = w; viewH = h
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = ShaderUtil.buildProgram(context, "shaders/vertex.glsl", "shaders/fragment.glsl")
        GLES20.glUseProgram(program)

        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uResolution = GLES20.glGetUniformLocation(program, "uResolution")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uParams = GLES20.glGetUniformLocation(program, "uParams")
        uFilm = GLES20.glGetUniformLocation(program, "uFilm")
        uShowRuleOfThirds = GLES20.glGetUniformLocation(program, "uShowRuleOfThirds")
        uGrainSizeLoc = GLES20.glGetUniformLocation(program, "uGrainSize")
        uGrainRoughnessLoc = GLES20.glGetUniformLocation(program, "uGrainRoughness")

        // Create external OES texture
        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        textureOES = texs[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOES)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTex = SurfaceTexture(textureOES).apply {
            setOnFrameAvailableListener(this@GLRenderer)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable.getAndSet(false)) {
            surfaceTex?.updateTexImage()
            surfaceTex?.getTransformMatrix(stMatrix)
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad as java.nio.Buffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex as java.nio.Buffer)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uResolution, viewW.toFloat(), viewH.toFloat())
        val t = (System.nanoTime() - timeStart) / 1_000_000_000.0f
        GLES20.glUniform1f(uTime, t)

        val p = params
        GLES20.glUniform4f(uParams, p.halation, p.bloom, 0f, p.exposure) // disable grain in preview
        GLES20.glUniform1f(uGrainSizeLoc, max(1f, min(2f, p.grainSize)))
        GLES20.glUniform1f(uGrainRoughnessLoc, p.grainRoughness)
        GLES20.glUniform4f(uFilm, p.film.contrast, p.film.saturation, p.film.shadowTint, p.film.highlightTint)
        GLES20.glUniform1i(uShowRuleOfThirds, if (p.showRuleOfThirds) 1 else 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOES)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }
    
    fun ensureFrameAvailable(): Boolean {
        // Wait for a frame to be available
        var attempts = 0
        while (!frameAvailable.get() && attempts < 60) { // Wait up to 2 seconds
            try {
                Thread.sleep(33) // Wait for ~30fps
                attempts++
            } catch (e: InterruptedException) {
                return false
            }
        }
        return frameAvailable.get()
    }

    fun provideSurface(requestSize: Size): Surface {
        surfaceTex?.setDefaultBufferSize(requestSize.width, requestSize.height)
        if (surface == null) surface = Surface(surfaceTex)
        return surface!!
    }

    fun captureBitmap(): Bitmap {
        // Check if we have a valid surface texture
        if (surfaceTex == null) {
            throw RuntimeException("Camera surface texture not initialized")
        }
        
        // Ensure we have valid viewport dimensions
        if (viewW <= 0 || viewH <= 0) {
            throw RuntimeException("Invalid viewport dimensions: ${viewW}x${viewH}")
        }
        
        // Ensure a frame is available
        if (!ensureFrameAvailable()) {
            throw RuntimeException("No camera frame available for capture")
        }
        
        // Update the texture with the latest frame
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTex?.updateTexImage()
                surfaceTex?.getTransformMatrix(stMatrix)
            } catch (e: Exception) {
                throw RuntimeException("Failed to update camera texture: ${e.message}")
            }
        }
        // Render current frame into an FBO and read pixels
        val w = viewW
        val h = viewH
        val fbo = IntArray(1)
        val tex = IntArray(1)
        
        // Generate and bind FBO
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        
        // Generate and bind texture
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        
        // Set texture parameters
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // Allocate texture storage
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        
        // Attach texture to FBO
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)

        // Check if FBO is complete
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            val errorMsg = when (status) {
                GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "Incomplete attachment"
                GLES20.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS -> "Incomplete dimensions"
                GLES20.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "Missing attachment"
                GLES20.GL_FRAMEBUFFER_UNSUPPORTED -> "Unsupported"
                else -> "Unknown error: $status"
            }
            throw RuntimeException("FBO not complete: $errorMsg")
        }

        // Set viewport for FBO
        GLES20.glViewport(0, 0, w, h)
        
        // Clear the FBO
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Draw the scene into FBO
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad as java.nio.Buffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex as java.nio.Buffer)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uResolution, viewW.toFloat(), viewH.toFloat())
        val t = (System.nanoTime() - timeStart) / 1_000_000_000.0f
        GLES20.glUniform1f(uTime, t)

        val p = params
        GLES20.glUniform4f(uParams, p.halation, p.bloom, 0f, p.exposure) // disable grain in preview
        GLES20.glUniform1f(uGrainSizeLoc, max(1f, min(2f, p.grainSize)))
        GLES20.glUniform1f(uGrainRoughnessLoc, p.grainRoughness)
        GLES20.glUniform4f(uFilm, p.film.contrast, p.film.saturation, p.film.shadowTint, p.film.highlightTint)
        GLES20.glUniform1i(uShowRuleOfThirds, if (p.showRuleOfThirds) 1 else 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOES)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Read pixels
        val buf = ByteBuffer.allocate(w * h * 4)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)

        // Clean up
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(1, tex, 0)

        // Flip vertically because GL has origin at bottom-left
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
    }
    
    fun captureBitmapSimple(): Bitmap {
        // Simple capture method that reads directly from the screen
        val w = viewW
        val h = viewH
        
        if (w <= 0 || h <= 0) {
            throw RuntimeException("Invalid viewport dimensions: ${w}x${h}")
        }
        
        // Check if we have a valid surface texture
        if (surfaceTex == null) {
            throw RuntimeException("Camera surface texture not initialized")
        }
        
        // Ensure a frame is available
        if (!ensureFrameAvailable()) {
            throw RuntimeException("No camera frame available for capture")
        }
        
        // Update the texture with the latest frame
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTex?.updateTexImage()
                surfaceTex?.getTransformMatrix(stMatrix)
            } catch (e: Exception) {
                throw RuntimeException("Failed to update camera texture: ${e.message}")
            }
        }
        
        // Ensure we're rendering to the screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        
        // Clear the screen
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // Draw the scene
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad as java.nio.Buffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex as java.nio.Buffer)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uResolution, viewW.toFloat(), viewH.toFloat())
        val t = (System.nanoTime() - timeStart) / 1_000_000_000.0f
        GLES20.glUniform1f(uTime, t)

        val p = params
        GLES20.glUniform4f(uParams, p.halation, p.bloom, 0f, p.exposure) // disable grain in preview
        GLES20.glUniform1f(uGrainSizeLoc, max(1f, min(2f, p.grainSize)))
        GLES20.glUniform1f(uGrainRoughnessLoc, p.grainRoughness)
        GLES20.glUniform4f(uFilm, p.film.contrast, p.film.saturation, p.film.shadowTint, p.film.highlightTint)
        GLES20.glUniform1i(uShowRuleOfThirds, if (p.showRuleOfThirds) 1 else 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOES)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Flush to ensure rendering is complete
        GLES20.glFinish()
        
        // Read pixels directly from the screen
        val buf = ByteBuffer.allocate(w * h * 4)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        
        // Flip vertically because GL has origin at bottom-left
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
    }
    
    fun captureCurrentFrame(): Bitmap {
        // Capture the current frame that's being displayed
        val w = viewW
        val h = viewH
        
        if (w <= 0 || h <= 0) {
            throw RuntimeException("Invalid viewport dimensions: ${w}x${h}")
        }
        
        // Ensure we're reading from the current surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        
        // Force a frame to be rendered
        onDrawFrame(null)
        
        // Flush to ensure rendering is complete
        GLES20.glFinish()
        
        // Read pixels from the current surface
        val buf = ByteBuffer.allocate(w * h * 4)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        
        // Flip vertically because GL has origin at bottom-left
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
    }
}

data class EffectParams(
    val halation: Float = 0.2f,
    val bloom: Float = 0.3f,
    val grain: Float = 0.15f,
    val grainSize: Float = 1.5f,
    val grainRoughness: Float = 0.5f,
    val exposure: Float = 0.0f,
    val film: Film = FilmSim.PROVIA.film,
    val showRuleOfThirds: Boolean = false
)

enum class FilmSim(val displayName: String, val film: Film) {
    PROVIA("ProView Neutral", Film(contrast=1.0f, saturation=1.0f, shadowTint=0.00f, highlightTint=0.00f)),
    VELVIA("Velora Vivid",   Film(contrast=1.15f, saturation=1.35f, shadowTint=-0.03f, highlightTint=0.02f)),
    ASTIA("Asteria Soft",      Film(contrast=0.95f, saturation=0.95f, shadowTint=0.02f, highlightTint=0.04f)),
    CLASSIC_CHROME("Soft Chrome", Film(contrast=1.05f, saturation=0.75f, shadowTint=0.02f, highlightTint=-0.02f)),
    PRO_NEG_STD("Pro Portrait Std", Film(contrast=0.95f, saturation=0.85f, shadowTint=0.01f, highlightTint=0.01f)),
    PRO_NEG_HI("Pro Portrait Hi",   Film(contrast=1.10f, saturation=0.9f, shadowTint=0.00f, highlightTint=0.01f)),
    ETERNA("Eternis Cine",  Film(contrast=0.90f, saturation=0.65f, shadowTint=0.02f, highlightTint=-0.01f)),
    CLASSIC_NEG("Retro Negative", Film(contrast=1.10f, saturation=0.8f, shadowTint=0.03f, highlightTint=-0.02f)),
    NOSTALGIC_NEG("Nostalgia Negative", Film(contrast=0.95f, saturation=1.05f, shadowTint=0.04f, highlightTint=0.06f)),
    ACROS("Acrux B&W",         Film(contrast=1.10f, saturation=0.0f, shadowTint=0.00f, highlightTint=0.00f));
    companion object {
        val valuesList = values().toList()
    }
}

data class Film(
    val contrast: Float,
    val saturation: Float,
    val shadowTint: Float,
    val highlightTint: Float
)

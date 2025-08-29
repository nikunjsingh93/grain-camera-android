package com.graincamera

import android.content.Context
import android.util.AttributeSet
 
import android.view.ViewGroup
import android.widget.FrameLayout
import com.graincamera.gl.CameraGLSurfaceView

class AspectRatioGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    
    private val glSurfaceView: CameraGLSurfaceView
    
    init {
        glSurfaceView = CameraGLSurfaceView(context)
        addView(glSurfaceView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Container doesn't need to intercept by default; we'll attach listener to the inner GLSurfaceView
        isClickable = false
    }
    
    val renderer: com.graincamera.gl.GLRenderer
        get() = glSurfaceView.renderer
    
    fun setZOrderOnTop(onTop: Boolean) {
        glSurfaceView.setZOrderOnTop(onTop)
    }
    
    

    fun setCameraTouchListener(listener: OnTouchListener) { /* removed: no-op */ }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        // Calculate 3:4 aspect ratio (portrait orientation)
        val aspectRatio = 3.0f / 4.0f
        
        var newWidth: Int
        var newHeight: Int
        
        // Always prioritize height and constrain width to maintain 3:4 aspect ratio
        newHeight = height
        newWidth = (height * aspectRatio).toInt()
        
        // If the calculated width exceeds available width, then constrain by width instead
        if (newWidth > width) {
            newWidth = width
            newHeight = (width / aspectRatio).toInt()
        }
        
        // Measure the GLSurfaceView with the calculated dimensions
        val childWidthSpec = MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY)
        glSurfaceView.measure(childWidthSpec, childHeightSpec)
        
        // Set our own dimensions
        setMeasuredDimension(newWidth, newHeight)
    }
}

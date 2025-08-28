package com.graincamera.gl

import android.content.Context
import android.opengl.GLES20
import java.io.BufferedReader
import java.io.InputStreamReader

object ShaderUtil {
    private fun readAsset(context: Context, path: String): String {
        context.assets.open(path).use { ins ->
            return BufferedReader(InputStreamReader(ins)).readText()
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: " + log)
        }
        return shader
    }

    fun buildProgram(context: Context, vertexAsset: String, fragmentAsset: String): Int {
        val vsrc = readAsset(context, vertexAsset)
        val fsrc = readAsset(context, fragmentAsset)
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glBindAttribLocation(prog, 0, "aPosition")
        GLES20.glBindAttribLocation(prog, 1, "aTexCoord")
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: " + log)
        }
        return prog
    }
}

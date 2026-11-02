package com.example.imagetracking

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.huawei.hiar.ARFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Рисует поток камеры на весь экран (внешняя текстура OES), как в демо AR Engine. */
class CameraBackground {
    var textureId = 0
        private set
    private var program = 0
    private var aPos = 0
    private var aCoord = 0

    private val posBuf: FloatBuffer =
        Gl.floatBuffer(floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f))
    private val texBuf: FloatBuffer =
        Gl.floatBuffer(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
    private val texTransformed: FloatBuffer =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun init() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = Gl.program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "vPosition")
        aCoord = GLES20.glGetAttribLocation(program, "vCoord")
    }

    fun draw(frame: ARFrame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformDisplayUvCoords(texBuf, texTransformed)
        }
        texTransformed.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aCoord)
        GLES20.glVertexAttribPointer(aCoord, 2, GLES20.GL_FLOAT, false, 0, texTransformed)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCoord)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 vPosition;
            attribute vec2 vCoord;
            varying vec2 textureCoordinate;
            void main() {
                gl_Position = vec4(vPosition.xy, 0.0, 1.0);
                textureCoordinate = vCoord;
            }
        """
        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoordinate;
            uniform samplerExternalOES vTexture;
            void main() {
                gl_FragColor = texture2D(vTexture, textureCoordinate);
            }
        """
    }
}

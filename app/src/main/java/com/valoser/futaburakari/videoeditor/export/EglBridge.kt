package com.valoser.futaburakari.videoeditor.export

import android.opengl.EGLExt
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Note: This is a simplified EGL bridge for demonstration.
// For robust production use, consider more comprehensive error handling and resource management.

/**
 * Holds state associated with a Surface used for MediaCodec encoder input.
 *
 * The constructor for this class must be called on a thread with an active EGL context.
 */
class EncoderInputSurface(private val surface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var decoderPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var width: Int = -1
    private var height: Int = -1

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val EGL_RECORDABLE_ANDROID = 0x3142
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, // Support both window and pbuffer
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            throw RuntimeException("unable to find suitable EGLConfig")
        }
        eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs, 0
        )

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)

        // Create a 1x1 pbuffer surface for the decoder to make current
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        decoderPbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)

        width = getSurfaceWidth(encoderEglSurface)
        height = getSurfaceHeight(encoderEglSurface)
    }

    fun eglDisplay(): EGLDisplay = eglDisplay
    fun eglContext(): EGLContext = eglContext
    fun eglConfig(): EGLConfig = eglConfig
        ?: throw IllegalStateException("EGLConfig is not initialized")
    fun encoderEglSurface(): EGLSurface = encoderEglSurface
    fun decoderPbufferSurface(): EGLSurface = decoderPbufferSurface

    private fun getSurfaceWidth(eglSurface: EGLSurface): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, value, 0)
        return value[0]
    }

    private fun getSurfaceHeight(eglSurface: EGLSurface): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, value, 0)
        return value[0]
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        // 旧サーフェス解除
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        // 新サーフェスを current に
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, nsecs)
    }

    fun getWidth(): Int = width
    fun getHeight(): Int = height

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            EGL14.eglDestroySurface(eglDisplay, decoderPbufferSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        decoderPbufferSurface = EGL14.EGL_NO_SURFACE
    }
}

/**
 * Manages a SurfaceTexture, an EGL context, and a Surface for decoding video.
 */
class DecoderOutputSurface(
    private val encoderWidth: Int,
    private val encoderHeight: Int,
    private val sharedEglDisplay: EGLDisplay,
    private val sharedEglContext: EGLContext,
    private val sharedEglConfig: EGLConfig,
    private val decoderPbufferSurface: EGLSurface
) : SurfaceTexture.OnFrameAvailableListener {
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var surface: Surface
        private set

    private val frameSyncObject = Object()
    private var frameAvailable = false

    private lateinit var textureRender: TextureRender
    private val texMatrix = FloatArray(16)

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    init {
        setup()
    }

    private fun setup() {
        // ★ 重要：GLリソース（シェーダ／テクスチャ）を作る前に必ず current にする
        EGL14.eglMakeCurrent(
            sharedEglDisplay,
            decoderPbufferSurface,  // draw
            decoderPbufferSurface,  // read
            sharedEglContext
        )

        textureRender = TextureRender()
        surfaceTexture = SurfaceTexture(textureRender.textureId)

        handlerThread = HandlerThread("DecoderOutputSurface").apply { start() }
        handler = Handler(handlerThread.looper)
        surfaceTexture.setOnFrameAvailableListener(this, handler)
        surfaceTexture.setDefaultBufferSize(encoderWidth, encoderHeight) // Set default buffer size
        surface = Surface(surfaceTexture)
    }

    fun release() {
        // EGL resources are managed by EncoderInputSurface, so no need to destroy here
        surfaceTexture.release()
        surface.release()
        textureRender.release()

        handlerThread.quitSafely()
        handlerThread.join() // Wait for the thread to finish
    }

    fun awaitNewImage(encoderInputSurface: EncoderInputSurface) {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(5000) // Timeout 5 seconds
                    if (!frameAvailable) {
                        throw RuntimeException("Frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    throw RuntimeException(ie)
                }
            }
            frameAvailable = false
        }
        encoderInputSurface.makeCurrent(decoderPbufferSurface)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
    }

    fun drawImage(encoderInputSurface: EncoderInputSurface) {
        encoderInputSurface.makeCurrent(encoderInputSurface.encoderEglSurface())
        GLES20.glViewport(0, 0, encoderInputSurface.getWidth(), encoderInputSurface.getHeight())
        textureRender.drawFrame(texMatrix)
        encoderInputSurface.setPresentationTime(surfaceTexture.timestamp)
        encoderInputSurface.swapBuffers()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameSyncObject) {
            if (frameAvailable) {
                // Handle case where previous frame was not consumed
            }
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }
}

class TextureRender {
    private val vertexShader = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val program: Int
    val textureId: Int
    private val mvpMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private val uMvpMatrixLoc: Int
    private val uTexMatrixLoc: Int
    private val aPositionLoc: Int
    private val aTexCoordLoc: Int

    init {
        val vb = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val tb = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vb.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vb).position(0)
        texCoordBuffer = ByteBuffer.allocateDirect(tb.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoordBuffer.put(tb).position(0)

        Matrix.setIdentityM(mvpMatrix, 0)

        program = createProgram(vertexShader, fragmentShader)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun drawFrame(texMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }
    
    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, pixelShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: " + info)
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader " + shaderType + ":" + info)
        }
        return shader
    }
}

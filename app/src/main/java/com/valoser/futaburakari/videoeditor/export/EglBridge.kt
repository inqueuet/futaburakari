package com.valoser.futaburakari.videoeditor.export

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import android.opengl.Matrix

/**
 * エンコーダ入力用の EGL ラッパ
 * 共有元 EGLContext を指定できるようにするのが最大のポイント
 */
class EncoderInputSurface(
    private val surface: Surface,
    private val sharedContext: EGLContext? = null
){
    private val TAG = "EncoderInputSurface"
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        EglRefManager.addRef(eglDisplay)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val share = sharedContext ?: EGL14.EGL_NO_CONTEXT
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, share, ctxAttribs, 0)
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed (shared=${sharedContext!=null}) err=0x${Integer.toHexString(EGL14.eglGetError())}")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }

        makeCurrent()
        Log.d(TAG, "setup: shared=${sharedContext!=null}")
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(encoder) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    fun setPresentationTime(nsec: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsec)
    }

    fun swapBuffers(): Boolean {
        val ok = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!ok) Log.e(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        return ok
    }

    fun release() {
        try {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EglRefManager.releaseDisplay(eglDisplay)
            }
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // アクセサ（ExportPipeline から利用）
    fun eglDisplay(): EGLDisplay = eglDisplay
    fun eglContext(): EGLContext = eglContext
    fun encoderEglSurface(): EGLSurface = eglSurface
    fun eglConfig(): EGLConfig? = eglConfig
}

/**
 * デコーダ出力用の EGL ラッパ（OES -> 2D へ描画）
 * EncoderInputSurface と「共有チェーン上」に作ることが重要
 */
class DecoderOutputSurface(
    private val width: Int,
    private val height: Int,
    private val sharedContext: EGLContext? = null
) : SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "DecoderOutputSurface"

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTexId: Int = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var surface: Surface
        private set

    private var frameAvailable = false
    private val frameSync = Object()
    // --- OES 描画用 ---
    private var program = 0
    private var aPosLoc = -1
    private var aTexLoc = -1
    private var uTexMatrixLoc = -1
    private var uSamplerLoc = -1   // ★ sTexture の uniform ロケーション
    private val texMatrix = FloatArray(16)
    private val correctedTexMatrix = FloatArray(16) // ★ 反転補正後を入れる一時配列
    private lateinit var vb: java.nio.FloatBuffer
    private lateinit var tb: java.nio.FloatBuffer

    // ★ 元動画のアスペクト比を保存
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        EglRefManager.addRef(eglDisplay)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val share = sharedContext ?: EGL14.EGL_NO_CONTEXT
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, share, ctxAttribs, 0)
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext(decoder) failed (shared=${sharedContext!=null})")
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // OES テクスチャ + SurfaceTexture
        oesTexId = createOesTex()
        surfaceTexture = SurfaceTexture(oesTexId)
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)
        Log.d(TAG, "setup: ${width}x${height}, shared=${sharedContext!=null})")

        // OES描画初期化
        initRenderer()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameSync) {
            frameAvailable = true
            frameSync.notifyAll()
        }
    }

    /**
     * 元動画のサイズを設定（アスペクト比計算用）
     */
    fun setSourceAspectRatio(srcWidth: Int, srcHeight: Int) {
        sourceWidth = srcWidth
        sourceHeight = srcHeight
        // ★ vbが未初期化の場合は初期化を促す
        if (!this::vb.isInitialized) {
            Log.w(TAG, "setSourceAspectRatio called before initRenderer, aspect ratio will be applied on init")
        } else {
            updateVertexBuffer()
        }
    }

/**
 * アスペクト比を保持するように頂点バッファを更新
 * ★ FIT モード：縦長・横長を自動判断してアスペクト比を保持
 */
private fun updateVertexBuffer() {
    if (sourceWidth == 0 || sourceHeight == 0) {
        // ソースサイズ未設定の場合はフルスクリーン
        
        val verts = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        
        if (!this::vb.isInitialized || vb.capacity() < verts.size * 4) {
            vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        } else {
            vb.clear()
            vb.position(0)
            vb.put(verts)
        }
        vb.position(0)
        return
    }

    // アスペクト比計算
    val srcAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
    val dstAspect = width.toFloat() / height.toFloat()

    var scaleX = 1f
    var scaleY = 1f

    // ★ FIT モード：アスペクト比を保持（自動判断）
    if (srcAspect > dstAspect) {
        // 元動画の方が横長 → 左右フル、上下に余白（レターボックス）
        scaleY = dstAspect / srcAspect  // < 1（縮小）
    } else {
        // 元動画の方が縦長 → 上下フル、左右に余白（ピラーボックス）
        scaleX = srcAspect / dstAspect  // < 1（縮小）
    }

    // NDC座標で頂点を設定
    val verts = floatArrayOf(
        -scaleX, -scaleY,
         scaleX, -scaleY,
        -scaleX,  scaleY,
         scaleX,  scaleY
    )
    vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
    vb.position(0)

    val mode = if (srcAspect > dstAspect) "横長動画→レターボックス" else "縦長動画→ピラーボックス"
    Log.d(TAG, "updateVertexBuffer: src=${sourceWidth}x${sourceHeight} (${srcAspect}), " +
            "dst=${width}x${height} (${dstAspect}), scale=(${scaleX}, ${scaleY}) [$mode]")
}

    fun awaitNewImage(encoder: EncoderInputSurface) {
        // デコーダの egl を current に
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(decoder) failed")
        }
        val timeoutMs = 2500L
        val start = System.nanoTime()
        synchronized(frameSync) {
            while (!frameAvailable) {
                frameSync.wait(50)
                if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) {
                    throw RuntimeException("frame wait timed out")
                }
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
    }

    fun drawImage(encoder: EncoderInputSurface) {
        // エンコーダ面を current にして描画
        encoder.makeCurrent()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_BLEND)
        // ★ OES テクスチャを TEXTURE0 に明示的にバインド
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

        // ★ 縦反転の補正を掛けてから uTexMatrix へ
        //    flipV = Translate(0,1) * Scale(1,-1)  （テクスチャ座標のYを上下反転）
        val flipV = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        Matrix.translateM(flipV, 0, 0f, 1f, 0f)
        Matrix.scaleM(flipV, 0, 1f, -1f, 1f)
        // GLSL で使用するのは (uTexMatrix * vec4(texCoord,0,1)) なので、
        // 先に flip を適用 → その後に SurfaceTexture の変換、の順にするには右側に flip を掛ける
        Matrix.multiplyMM(correctedTexMatrix, 0, texMatrix, 0, flipV, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, correctedTexMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        try {
            if (this::surface.isInitialized) surface.release()
            if (this::surfaceTexture.isInitialized) surfaceTexture.release()
            if (oesTexId != 0) {
                val tmp = IntArray(1); tmp[0] = oesTexId
                GLES20.glDeleteTextures(1, tmp, 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                // 安全な EGL 解放（参照カウント管理）
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )

                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }

                // ディスプレイの参照カウントを減らす（0 になったら eglTerminate）
                EglRefManager.releaseDisplay(eglDisplay)
            }
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun createOesTex(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    // ------------------------
    // OES 描画ユーティリティ
    // ------------------------
    private fun initRenderer() {
        // 初期は頂点バッファを空で作成（後で updateVertexBuffer で更新）
        updateVertexBuffer()

        val tex = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        tb = java.nio.ByteBuffer.allocateDirect(tex.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(tex)
        tb.position(0)

        val vsrc = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main(){
              gl_Position = aPosition;
              vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fsrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main(){
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            val link = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, link, 0)
            if (link[0] != GLES20.GL_TRUE) {
                val msg = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                throw RuntimeException("program link failed: $msg")
            }
        }
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uSamplerLoc = GLES20.glGetUniformLocation(program, "sTexture") // ★ 追加
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uSamplerLoc, 0) // ★ sTexture は TEXTURE0 を参照（固定）
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val msg = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("shader compile failed: $msg")
        }
        return sh
    }
}

/**
 * EGLDisplay の参照カウントを管理し、共有時の二重 terminate を防ぐ。
 */
object EglRefManager {
    private val refCounts = java.util.concurrent.ConcurrentHashMap<EGLDisplay, Int>()

    @Synchronized
    fun addRef(display: EGLDisplay) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        refCounts[display] = (refCounts[display] ?: 0) + 1
        Log.d("EglRefManager", "addRef: $display -> ${refCounts[display]}")
    }

    @Synchronized
    fun releaseDisplay(display: EGLDisplay) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        val next = (refCounts[display] ?: 0) - 1
        if (next <= 0) {
            refCounts.remove(display)
            Log.d("EglRefManager", "terminate: $display (ref=0)")
            try {
                EGL14.eglTerminate(display)
            } catch (e: Exception) {
                Log.w("EglRefManager", "eglTerminate failed: $e")
            }
        } else {
            refCounts[display] = next
            Log.d("EglRefManager", "releaseDisplay: $display -> $next")
        }
    }
}
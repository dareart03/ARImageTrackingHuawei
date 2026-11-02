package com.example.imagetracking

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.huawei.hiar.ARAugmentedImage
import com.huawei.hiar.ARFrame
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARTrackable
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Распознавание картинки-метки (Image Tracking) и показ 3D-модели поверх неё.
 *
 * В режиме ARImageTrackingConfig поза метки задана относительно камеры, поэтому
 * (как в демо AR Engine) матрица вида — единичная, а модель рисуется в позе метки.
 * Модель появляется, пока метка распознана (TRACKING), и исчезает, когда метка потеряна.
 * Вся логика — в GL-потоке; события касаний приходят через очередь.
 */
class ImageRenderer(private val context: Context, private val callbacks: Callbacks) :
    GLSurfaceView.Renderer {

    interface Callbacks {
        fun onStatus(text: String)
        fun onMessage(text: String)
    }

    @Volatile var session: ARSession? = null
    @Volatile var displayRotation = 0
    @Volatile var touchSlop = 24f
    @Volatile private var geometryDirty = true

    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private val background = CameraBackground()

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aColor = 0
    private var uMvp = 0
    private var uModel = 0
    private var uTint = 0
    private var uLight = 0
    private var rocket: ObjModel? = null
    private lateinit var quad: ObjModel

    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)
    private var frameCounter = 0

    // найденные метки: индекс в базе → объект метки
    private val tracked = HashMap<Int, ARAugmentedImage>()

    // состояние модели (переживает потерю метки)
    private var modelVisible = false
    private val modelMatrix = FloatArray(16)
    private var modelSize = 0.15f
    private var userYaw = 0f
    private var userScale = 1f
    private var tintIndex = 0
    private var tapCount = 0
    private var bounceStart = 0L

    // жесты
    private var tapAllowed = false
    private var pinching = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downTime = 0L
    private var prevDist = 0f

    // ===================== UI-поток =====================

    fun onDisplayChanged() {
        geometryDirty = true
    }

    fun onTouchEvent(e: MotionEvent) {
        val action = e.actionMasked
        val n = e.pointerCount
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = if (n > 1) e.getX(1) else 0f
        val y1 = if (n > 1) e.getY(1) else 0f
        val t = e.eventTime
        queue.add { handleTouch(action, n, x0, y0, x1, y1, t) }
    }

    // ===================== GLSurfaceView.Renderer =====================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        background.init()
        program = Gl.program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        rocket = try {
            ObjLoader.load(context, "rocket.obj")
        } catch (e: Exception) {
            Log.e(TAG, "model load failed: $e")
            callbacks.onMessage("Не удалось загрузить модель: ${e.javaClass.simpleName}")
            null
        }
        // единичный квадрат в плоскости XZ — подсветка распознанной картинки
        val q = floatArrayOf(
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            -0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f
        )
        quad = ObjModel(q)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryDirty) {
            s.setDisplayGeometry(displayRotation, width, height)
            geometryDirty = false
        }
        s.setCameraTextureName(background.textureId)
        val frame: ARFrame = try {
            s.update()
        } catch (e: Exception) {
            Log.w(TAG, "session.update failed: $e")
            return
        }
        background.draw(frame)

        val camera = frame.getCamera()
        if (camera.getTrackingState() == ARTrackable.TrackingState.PAUSED) {
            modelVisible = false
            queue.clear()
            reportStatus(false)
            return
        }
        camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        Matrix.setIdentityM(view, 0) // поза метки — относительно камеры

        // обновляем список распознанных меток
        for (img in frame.getUpdatedTrackables(ARAugmentedImage::class.java)) {
            when (img.getTrackingState()) {
                ARTrackable.TrackingState.TRACKING -> tracked[img.getIndex()] = img
                ARTrackable.TrackingState.STOPPED -> tracked.remove(img.getIndex())
                else -> {}
            }
        }
        val active = tracked.values.firstOrNull {
            it.getTrackingState() == ARTrackable.TrackingState.TRACKING
        }

        if (active == null) {
            modelVisible = false
        } else {
            updateModelMatrix(active)
        }

        while (true) {
            val task = queue.poll() ?: break
            try {
                task()
            } catch (e: Exception) {
                Log.w(TAG, "task failed: $e")
            }
        }

        if (active != null) drawScene(active)
        reportStatus(active != null)
    }

    // ===================== модель =====================

    /** Модель стоит на картинке и «растёт» от неё по нормали (ось Y позы метки). */
    private fun updateModelMatrix(img: ARAugmentedImage) {
        val w = img.getExtentX()
        modelSize = (if (w > 0.01f) w else 0.15f) * userScale
        img.getCenterPose().toMatrix(model, 0)

        // подпрыгивание после касания
        val now = SystemClock.uptimeMillis()
        val t = (now - bounceStart) / 700f
        val bounce = if (t in 0f..1f) sin(Math.PI * t).toFloat() * 0.35f * (1f - t) else 0f

        Matrix.translateM(model, 0, 0f, bounce * modelSize, 0f)
        Matrix.rotateM(model, 0, userYaw, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, modelSize, modelSize, modelSize)
        System.arraycopy(model, 0, modelMatrix, 0, 16)
        modelVisible = true
    }

    private fun drawScene(img: ARAugmentedImage) {
        val m = rocket ?: return
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLight, 0.2f, 0.6f, 1f)

        val tint = TINTS[tintIndex % TINTS.size]
        System.arraycopy(modelMatrix, 0, model, 0, 16)
        draw(m, tint[0], tint[1], tint[2], 1f)

        // полупрозрачная подсветка распознанной картинки
        val ew = img.getExtentX()
        val ez = img.getExtentZ()
        if (ew > 0.01f && ez > 0.01f) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            img.getCenterPose().toMatrix(model, 0)
            Matrix.translateM(model, 0, 0f, 0.0005f, 0f)
            Matrix.scaleM(model, 0, ew, 1f, ez)
            draw(quad, 0.1f, 0.9f, 0.8f, 0.25f)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun draw(mesh: ObjModel, r: Float, g: Float, b: Float, a: Float) {
        val vm = FloatArray(16)
        Matrix.multiplyMM(vm, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, vm, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uTint, r, g, b, a)
        val stride = ObjModel.FLOATS * 4
        mesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        mesh.buffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        mesh.buffer.position(6)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    // ===================== касания =====================

    private fun handleTouch(action: Int, n: Int, x0: Float, y0: Float, x1: Float, y1: Float, t: Long) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                tapAllowed = true
                pinching = false
                downX = x0; downY = y0; lastX = x0; downTime = t
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapAllowed = false
                if (n >= 2) {
                    pinching = true
                    prevDist = hypot(x1 - x0, y1 - y0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!modelVisible) return
                if (pinching && n >= 2) {
                    // два пальца — масштаб
                    val d = hypot(x1 - x0, y1 - y0)
                    if (prevDist > 1f) userScale = (userScale * d / prevDist).coerceIn(0.4f, 3f)
                    prevDist = d
                } else if (n == 1) {
                    // один палец — поворот вокруг вертикальной оси модели
                    if (hypot(x0 - downX, y0 - downY) > touchSlop) tapAllowed = false
                    if (!tapAllowed) userYaw += (x0 - lastX) * 0.5f
                    lastX = x0
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinching = false
                lastX = x0
            }
            MotionEvent.ACTION_UP -> {
                if (tapAllowed && (t - downTime) < 400L && modelVisible && hitsModel(x0, y0)) {
                    tapCount++
                    tintIndex++
                    bounceStart = SystemClock.uptimeMillis()
                    callbacks.onMessage("Касание модели №$tapCount")
                }
                pinching = false
            }
            MotionEvent.ACTION_CANCEL -> pinching = false
        }
    }

    /** Луч из точки касания против описанной сферы модели. */
    private fun hitsModel(x: Float, y: Float): Boolean {
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, vp, 0)) return false
        val nx = 2f * x / width - 1f
        val ny = 1f - 2f * y / height
        val near = unproject(inv, nx, ny, -1f)
        val far = unproject(inv, nx, ny, 1f)
        var dx = far[0] - near[0]
        var dy = far[1] - near[1]
        var dz = far[2] - near[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return false
        dx /= len; dy /= len; dz /= len

        // центр модели (0, 0.5, 0) в системе модели → пространство камеры
        val c = FloatArray(4)
        Matrix.multiplyMV(c, 0, modelMatrix, 0, floatArrayOf(0f, 0.5f, 0f, 1f), 0)
        val radius = modelSize * 0.6f
        val ox = near[0] - c[0]
        val oy = near[1] - c[1]
        val oz = near[2] - c[2]
        val b = ox * dx + oy * dy + oz * dz
        val cc = ox * ox + oy * oy + oz * oz - radius * radius
        return b * b - cc >= 0f && (-b + sqrt(b * b - cc)) >= 0f
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(x, y, z, 1f), 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    // ===================== статус =====================

    private fun reportStatus(found: Boolean) {
        if (++frameCounter % 10 != 0) return
        callbacks.onStatus(
            if (found) "Метка распознана — модель показана | Касаний: $tapCount\n" +
                "Тап по модели — реакция, палец — поворот, два пальца — масштаб"
            else "Наведите камеру на картинку-метку"
        )
    }

    private companion object {
        const val TAG = "ImageRenderer"

        val TINTS = arrayOf(
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 0.85f, 0.35f),
            floatArrayOf(0.55f, 1f, 0.6f),
            floatArrayOf(1f, 0.6f, 0.9f)
        )

        const val VERTEX = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vN;
            varying vec3 vColor;
            void main() {
                vN = (uModel * vec4(aNormal, 0.0)).xyz;
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 uTint;
            uniform vec3 uLight;
            varying vec3 vN;
            varying vec3 vColor;
            void main() {
                float d = max(dot(normalize(vN), normalize(uLight)), 0.0);
                float l = 0.35 + 0.65 * d;
                gl_FragColor = vec4(vColor * uTint.rgb * l, uTint.a);
            }
        """
    }
}

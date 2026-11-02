package com.example.imagetracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.huawei.hiar.ARAugmentedImageDatabase
import com.huawei.hiar.ARConfigBase
import com.huawei.hiar.AREnginesApk
import com.huawei.hiar.ARImageTrackingConfig
import com.huawei.hiar.ARSession
import com.huawei.hiar.exceptions.ARCameraNotAvailableException
import com.huawei.hiar.exceptions.ARUnSupportedConfigurationException
import com.huawei.hiar.exceptions.ARUnavailableClientSdkTooOldException
import com.huawei.hiar.exceptions.ARUnavailableServiceApkTooOldException
import com.huawei.hiar.exceptions.ARUnavailableServiceNotInstalledException

/**
 * Распознавание картинки-метки на HUAWEI AR Engine (Image Tracking).
 * Метка — assets/marker.png; при её распознавании над ней появляется 3D-модель (assets/rocket.obj).
 */
class MainActivity : AppCompatActivity(), ImageRenderer.Callbacks {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var ivMarker: ImageView
    private lateinit var renderer: ImageRenderer

    private var session: ARSession? = null
    private var installDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        ivMarker = findViewById(R.id.ivMarker)

        renderer = ImageRenderer(this, this)
        renderer.touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setOnTouchListener { _, e ->
            renderer.onTouchEvent(e)
            true
        }

        // показать метку на экране (чтобы навести на неё камеру другого телефона)
        ivMarker.setImageBitmap(loadMarker())
        val btnMarker = findViewById<Button>(R.id.btnMarker)
        btnMarker.setOnClickListener {
            val show = ivMarker.visibility != View.VISIBLE
            ivMarker.visibility = if (show) View.VISIBLE else View.GONE
            btnMarker.text = if (show) "Скрыть метку" else "Показать метку"
        }
    }

    private fun loadMarker(): Bitmap? = try {
        assets.open("marker.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    override fun onResume() {
        super.onResume()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }

        if (!AREnginesApk.isAREngineApkReady(this)) {
            showInstallDialog()
            return
        }
        installDialog?.dismiss()
        installDialog = null

        if (session == null) {
            try {
                val s = ARSession(applicationContext)
                // Image Tracker: конфигурация распознавания картинок + база эталонных изображений
                val cfg = ARImageTrackingConfig(s)
                cfg.setFocusMode(ARConfigBase.FocusMode.AUTO_FOCUS)
                val bitmap = loadMarker()
                if (bitmap == null) {
                    toast("Не найден assets/marker.png")
                    return
                }
                val db = ARAugmentedImageDatabase(s)
                db.addImage("marker", bitmap)
                cfg.setAugmentedImageDatabase(db)
                s.configure(cfg)
                session = s
            } catch (e: Exception) {
                handleSessionError(e)
                return
            }
        }

        try {
            session?.resume()
        } catch (e: ARCameraNotAvailableException) {
            toast("Камера недоступна, перезапустите приложение")
            session = null
            return
        } catch (e: Exception) {
            handleSessionError(e)
            return
        }

        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        renderer.displayRotation = rotation
        renderer.onDisplayChanged()
        renderer.session = session
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        installDialog?.dismiss()
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            toast("Нужен доступ к камере")
            finish()
        }
    }

    private fun handleSessionError(e: Exception) {
        when (e) {
            is ARUnavailableServiceNotInstalledException,
            is ARUnavailableServiceApkTooOldException -> showInstallDialog()
            is ARUnavailableClientSdkTooOldException -> toast("Обновите SDK AR Engine в приложении")
            is ARUnSupportedConfigurationException ->
                toast("Это устройство не поддерживает распознавание изображений в AR Engine")
            else -> toast("Ошибка AR Engine: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun showInstallDialog() {
        if (installDialog?.isShowing == true) return
        installDialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setMessage("Для работы нужно приложение HUAWEI AR Engine. Установить или обновить из AppGallery?")
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setPositiveButton("Установить") { _, _ ->
                try {
                    val intent = Intent("com.huawei.appmarket.intent.action.AppDetail")
                    intent.putExtra("APP_PACKAGENAME", "com.huawei.arengine.service")
                    intent.setPackage("com.huawei.appmarket")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    toast("AppGallery не найден — установите AR Engine вручную")
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- ImageRenderer.Callbacks (вызываются из GL-потока) ----

    override fun onStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    override fun onMessage(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val REQ_CAMERA = 1
    }
}

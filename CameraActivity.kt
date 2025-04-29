package com.yujin.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.yujin.capture.databinding.ActivityCameraBinding
import com.yujin.capture.utils.getPreviewOutputSize

/**
 * 预览
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding

    //后摄 : 0 ，前摄 : 1
    private val cameraId = "0"
    private val TAG = CameraActivity::class.java.simpleName
    private lateinit var cameraDevice: CameraDevice
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraManager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }
    private lateinit var session: CameraCaptureSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ActivityCompat.requestPermissions(
            this@CameraActivity,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            ),
            123
        )

        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                //设置宽高比
                setAspectRatio()
                //为了确保设置了大小，需要在主线程中初始化camera
                binding.root.post {
                    openCamera(cameraId)
                }
            }
        })

        binding.btnTakePicture.visibility = View.GONE
    }

    private fun setAspectRatio() {
        val previewSize = getPreviewOutputSize(
            binding.surfaceView.display,
            characteristics,
            SurfaceHolder::class.java
        )
        Log.d(TAG, "Selected preview size: $previewSize")
        binding.surfaceView.setAspectRatio(previewSize.width, previewSize.height)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview()
            }

            override fun onDisconnected(camera: CameraDevice) {
                this@CameraActivity.finish()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Toast.makeText(application, "openCamera Failed:$error", Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)
    }

    private fun startPreview() {
        //因为摄像头设备可以同时输出多个流，所以可以传入多个surface
        val targets = listOf(binding.surfaceView.holder.surface /*,这里可以传入多个surface*/)
        cameraDevice.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                //赋值session
                session = captureSession

                val captureRequest = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                ).apply { addTarget(binding.surfaceView.holder.surface) }

                //这将不断地实时发送视频流，直到会话断开或调用session.stoprepeat()
                session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(application,"session configuration failed",Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)
    }

    override fun onStop() {
        super.onStop()
        try {
            cameraDevice.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        //imageReaderThread.quitSafely()
    }
}
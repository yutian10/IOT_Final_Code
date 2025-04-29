package com.yujin.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import com.yujin.capture.databinding.ActivityCameraBinding
import com.yujin.capture.utils.OrientationLiveData
import com.yujin.capture.utils.Utils.computeExifOrientation
import com.yujin.capture.utils.getPreviewOutputSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.pow
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 预览
 */
class CameraMultiExpoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding

    //后摄 : 0 ，前摄 : 1
    private val cameraId = "0"
    private val TAG = CameraHDRActivity::class.java.simpleName
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

    private lateinit var imageReader: ImageReader
    //JPEG格式，所有相机必须支持JPEG输出，因此不需要检查
    private val pixelFormat = ImageFormat.RAW_SENSOR // ImageFormat.RAW_SENSOR //ImageFormat.JPEG
    //imageReader最大的图片缓存数
    private val IMAGE_BUFFER_SIZE: Int = 48
    //线程池
    private val threadPool = Executors.newCachedThreadPool()
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    private var frames: Int = 18
    private var evNumber: Int = 13
    private var evBase: Int = -6
    private var evStop: Int = 1
    private var aeList: List<Int> = listOf(0, -1, 1, 2, 0, -2, -3, 0, 3, 4, 0, -4, -5, 0, 5, 6, 0, -6)

    private var iso: Int = -1
    private var exposureTime: Long = 10000000L // note, responsibility of callers to check that this is within the valid min/max range
    private val fpsRange = Range(15, 30)
    private var captureMode: Int = 1 // 0 auto AE ; 1 base capture

    private val gammaCurvePoints = floatArrayOf( // 1 / 2.2
        0.0000f, 0.0000f,
        0.0667f, 0.2920f,
        0.1333f, 0.4002f,
        0.2000f, 0.4812f,
        0.2667f, 0.5484f,
        0.3333f, 0.6069f,
        0.4000f, 0.6594f,
        0.4667f, 0.7072f,
        0.5333f, 0.7515f,
        0.6000f, 0.7928f,
        0.6667f, 0.8317f,
        0.7333f, 0.8685f,
        0.8000f, 0.9035f,
        0.8667f, 0.9370f,
        0.9333f, 0.9691f,
        1.0000f, 1.0000f)
    private val toneCurve = TonemapCurve(gammaCurvePoints, gammaCurvePoints, gammaCurvePoints)

    //AE range and step
    private var aeCompensationRange: Range<Int> = Range(-24, 24)
    private var aeCompensationStep: Rational = Rational(1, 6)
    private var isoRange: Range<Int> ?= null //SENSOR_INFO_SENSITIVITY_RANGE
    private var exposureTimeRange: Range<Int> ?= null  //SENSOR_INFO_EXPOSURE_TIME_RANGE

    private val imageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath(), "/MultiExpo")

    var imageQueue: LinkedBlockingQueue<ImageData> = LinkedBlockingQueue()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    var oriImageQueue: LinkedBlockingQueue<ImageAndName> = LinkedBlockingQueue()
    private var saveImageNumber: Int = 0

    private fun setHDRSetting() {
        // get the evNumber and evStop
        if (evNumber == 2) {
            aeList = listOf(evBase, evBase + evStop)
        }
        else if (evNumber == 3) {
            aeList = listOf(evBase, evBase + evStop, evBase + evStop + evStop, evBase + evStop)
        }
        runOnUiThread {
            findViewById<TextView>(R.id.framesTextView).text = "Frames: $frames"
            findViewById<TextView>(R.id.evBaseTextView).text = "EV Base: ${evBase}"
            findViewById<TextView>(R.id.evNumberTextView).text = "EV Number: $evNumber"
            findViewById<TextView>(R.id.evStopTextView).text = "EV Stop: $evStop"
            findViewById<TextView>(R.id.evTextView).text = "EV: [-6, 6]"
            var captureModeText = "Mode: Auto AE"
            when (captureMode){
                0 -> {captureModeText = "Mode: Auto AE"}
                1 -> {captureModeText = "Mode: Expo Time"}
            }
            findViewById<TextView>(R.id.captureModeTextView).text = captureModeText
        }
    }

    private fun setHDRParameter() {
        val captureTextView = findViewById<TextView>(R.id.captureModeTextView)
        captureTextView.setOnClickListener {
            showSelectDialog()
            setHDRSetting()
        }
        setHDRSetting()
    }

    private fun showSelectDialog() {
        val options = arrayOf("Capture Mode: Auto AE", "Capture Mode: Expo Time")
        var selectedOption = captureMode
        AlertDialog.Builder(this)
            .setTitle("Choice capture mode")
            .setSingleChoiceItems(options, selectedOption) {_, which ->
                selectedOption = which
            }
            .setPositiveButton("OK") { dialog, _ ->
                captureMode = selectedOption
                var captureModeText = "Mode: Auto AE"
                when (captureMode){
                    0 -> {captureModeText = "Mode: Auto AE"}
                    1 -> {captureModeText = "Mode: Expo Time"}
                }
                findViewById<TextView>(R.id.captureModeTextView).text = captureModeText
                Toast.makeText(application, "Setting ${options[selectedOption]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun saveImage(imageData: ImageData) {
        val image = imageData.image
        val output = imageData.imageName
        val metadata = imageData.metadata
        val orientation = imageData.orientation

        if (image.format == ImageFormat.JPEG){
            try {
                Log.d(TAG, "save image path: ${output.absolutePath}")
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                FileOutputStream(output).use { it.write(bytes) }

                // If the result is a JPEG file, update EXIF metadata with orientation info
//                if (output.extension == "jpg") {
//                    val exif = ExifInterface(output.absolutePath)
//                    exif.setAttribute(
//                        ExifInterface.TAG_ORIENTATION, orientation.toString())
//                    exif.saveAttributes()
//                    Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
//                }

            } catch (exc: IOException) {
                Log.e(TAG, "Unable to write JPEG image to file", exc)
                throw exc
            }
        }
        else {
            val dngCreator = DngCreator(characteristics, metadata)
            try {
                Log.d(TAG, "save image path: ${output.absolutePath}")
                FileOutputStream(output).use { dngCreator.writeImage(it, image) }
            } catch (exc: IOException) {
                Log.e(TAG, "Unable to write JPEG image to file", exc)
                throw exc
            }
        }
    }

    private fun autoAECapture(captureRequestBuilder: CaptureRequest.Builder, captureRequests: MutableList<CaptureRequest>) {
        // get 0EV: iso and shutter speed
        // 3A
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 100)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_GAMMA, 1.0f)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_CURVE, toneCurve)
//             captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        // capture the base image, with 0 frame
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        captureRequests.add(captureRequestBuilder.build())

        for (i in 0 until frames) {
            val ae = aeList[i % aeList.size]

            val currAE = (ae * aeCompensationStep.denominator / aeCompensationStep.numerator).toInt()
            Log.d(TAG, "Current AE: $currAE")
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currAE)
            captureRequestBuilder.setTag(/* tag = */ "${currAE}")
            captureRequests.add(captureRequestBuilder.build())
        }
    }

    private fun baseAECapture(captureRequestBuilder: CaptureRequest.Builder, captureRequests: MutableList<CaptureRequest>) {
        // get 0EV: iso and shutter speed
        // 3A
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF) // AE manual
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
//        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
//        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 100)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_GAMMA, 1.0f)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_CURVE, toneCurve)
//             captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
//            captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        val base = 2.0
//        val captureRequests = mutableListOf<CaptureRequest>()

        // capture the base image, with 0 frame
        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
        captureRequests.add(captureRequestBuilder.build())

        for (i in 0 until frames) {
            val ae = aeList[i % aeList.size]

            val expTime: Long = (exposureTime.toDouble() * base.pow(ae)).toLong()
            val currAE: Int = (ae * aeCompensationStep.denominator / aeCompensationStep.numerator)
            Log.d(TAG, "Current AE: $currAE, ISO: $iso, exposureTime: $expTime")

            // AE manual
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expTime)
            captureRequestBuilder.setTag(/* tag = */ "$currAE")
            captureRequests.add(captureRequestBuilder.build())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ActivityCompat.requestPermissions(
            this@CameraMultiExpoActivity,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA
            ),
            123
        )
        // init
        setHDRParameter()
        if (pixelFormat == ImageFormat.RAW_SENSOR) {
            runOnUiThread {
                findViewById<TextView>(R.id.saveTextView).text = "RAW"
            }
        }
        else {
            runOnUiThread {
                findViewById<TextView>(R.id.saveTextView).text = "JPEG"
            }
        }
        setHDRSetting()

        aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)!!
        aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)!!
        Log.d(TAG, "aeCompensationRange: $aeCompensationRange, $aeCompensationStep") //aeCompensationRange: [-24, 24], 1/6

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        Log.d(TAG, "Exposure range: [${exposureRange?.lower}, ${exposureRange?.upper}]") //[26503, 8310343667]

        runOnUiThread {
            findViewById<TextView>(R.id.safeExpoTextView).text = "SafeExpo: [${exposureRange?.lower}, ${exposureRange?.upper}]"
            findViewById<TextView>(R.id.safeAETextView).text = "SafeAE: $aeCompensationRange * $aeCompensationStep"
        }

        if (!imageDir.exists()) {
            imageDir.mkdir()
        }

        // start image saving thread
        startImageProcessingThread()

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

        binding.btnTakePicture.setOnClickListener {
            setHDRSetting()
            saveImageNumber = 0
            // Flush any images left in the image reader
            @Suppress("ControlFlowWithEmptyBody")
            while (imageReader.acquireNextImage() != null) {
            }
            val sequenceFileName =  File(imageDir, SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date()))
            if (!sequenceFileName.exists()) {
                sequenceFileName.mkdir()
            }
            var frameCount = 0
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage()
//                val sdf = SimpleDateFormat("HH_mm_ss_SSS", Locale.US) //yyyy_MM_dd_HH_mm_ss_SSS
                var fileSubName = "IMG_${frameCount}"
                if (frameCount > 0) {
                    val ae = aeList[(frameCount - 1) % aeList.size]
                    fileSubName = "IMG_${frameCount}_${ae}"
                }
                var fileName = File(sequenceFileName, "$fileSubName.jpg") //_${sdf.format(Date())}
                if (image.format == ImageFormat.RAW_SENSOR) {
                    fileName = File(sequenceFileName, "$fileSubName.dng") //_${sdf.format(Date())}
                }
                oriImageQueue.offer(ImageAndName(image, fileName))
                frameCount += 1
            }, imageReaderHandler)

            val captureRequestBuilder = session.device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply { addTarget(imageReader.surface) }
            val captureRequests = mutableListOf<CaptureRequest>()
            when (captureMode) {
                1 -> {baseAECapture(captureRequestBuilder, captureRequests)}
                0 -> {autoAECapture(captureRequestBuilder, captureRequests)}
            }
            Log.d(TAG, "captureRequests size: ${captureRequests.size}")
            session.captureBurst(
                captureRequests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        saveImageNumber += 1
                        Toast.makeText(application, "Saving ${saveImageNumber}/${frames}", Toast.LENGTH_SHORT).show()
                        val imageAndName = oriImageQueue.take()
                        val image = imageAndName.image
                        val fileName = imageAndName.imageName

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)
                        imageQueue.offer(ImageData(image, result, exifOrientation, fileName))
                    }
                },
                cameraHandler
            )
        }

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(this, characteristics).apply {
            observe(this@CameraMultiExpoActivity, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    private fun startImageProcessingThread() {
        coroutineScope.launch {
            while (true) {
                val imageData = imageQueue.take() // Blocking call to get data from queue
                Log.d(TAG, "get image : ${imageData.imageName}")
                saveImage(imageData)
            }
        }
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
                this@CameraMultiExpoActivity.finish()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Toast.makeText(application, "openCamera Failed:$error", Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)
    }

    private fun startPreview() {
        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!.getOutputSizes(pixelFormat).maxByOrNull { it.height * it.width }!! //maxByOrNull

        imageReader = ImageReader.newInstance(
            size.width, size.height, pixelFormat, IMAGE_BUFFER_SIZE
        )

        val aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        Log.d(TAG, "aeCompensationRange: $aeCompensationRange, $aeCompensationStep") //aeCompensationRange: [-24, 24], 1/6

        //因为摄像头设备可以同时输出多个流，所以可以传入多个surface
        val targets = listOf(binding.surfaceView.holder.surface, imageReader.surface)
        cameraDevice.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                //赋值session
                session = captureSession

                val captureRequest = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                ).apply { addTarget(binding.surfaceView.holder.surface) }

                // 3A
                captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                captureRequest.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                captureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                // set 0 EV, do not lock the ae
                captureRequest.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                session.setRepeatingRequest(captureRequest.build(), previewCaptureCallback, cameraHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(application,"session configuration failed",Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)

        setHDRSetting()
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY)!!
            exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)!!

            runOnUiThread {
                findViewById<TextView>(R.id.isoTextView).text = "ISO: $iso"
                findViewById<TextView>(R.id.expoTextView).text = "Expo: ${exposureTime?.div(1000000.0)}ms"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            cameraDevice.close()
            imageReader.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        coroutineScope.cancel()
    }
}

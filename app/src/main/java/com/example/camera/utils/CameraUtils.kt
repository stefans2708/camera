package com.example.camera.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.example.camera.ImageProcessor
import com.example.camera.SmartSize
import com.example.camera.tflite.Classifier
import io.reactivex.disposables.CompositeDisposable
import kotlin.math.max
import kotlin.math.min


private val SIZE_1080P = SmartSize(1920, 1080)
private val DESIRED_PREVIEW_SIZE = Size(640, 480)
private const val MINIMAL_VALID_PREVIEW_SIZE = 320  // empiric value

class CameraUtils(
    private val applicationContext: Context,
    private val cameraHandler: Handler,
    private val imageReaderHandler: Handler,
    private val eventListener: EventListener,
) : ImageReader.OnImageAvailableListener {

    private val imageProcessor by lazy { ImageProcessor(applicationContext) }
    private val compositeDisposable = CompositeDisposable()

    private var orientation: Int = 270
    private lateinit var camera: CameraDevice
    private lateinit var imageReader: ImageReader
    private lateinit var previewSize: Size
    private val cameraManager: CameraManager by lazy {
        applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    fun onTextureViewAvailable(defaultDisplayOrientation: Int) {
        setupCamera(defaultDisplayOrientation)
    }

    fun stopPreview() {
        camera.close()
    }

    fun saveImage() {
        ImageUtils.saveBitmap(applicationContext, bitmap, -orientation)
    }

    fun tearDown() {
        compositeDisposable.dispose()
    }

    private fun setupCamera(defaultDisplayOrientation: Int) {
        val cameraId = getCameraId()
        if (cameraId == null) {
            eventListener.onError("No appropriate camera available!")
            return
        }

        orientation = defaultDisplayOrientation - getSensorOrientation(cameraId)
        Log.d(TAG, "Orientation: $orientation")
        previewSize = getSmallestValidOutputSize(cameraId)
        Log.d(TAG, "Selected preview size: $previewSize")
        eventListener.onPreviewSizeSelected(previewSize)
        openCamera(cameraId) // todo: post removed
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera $cameraId is open")
                camera = cameraDevice
                initPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                "Camera $cameraId error: ($error) $msg".let { message ->
                    Log.e(TAG, message, RuntimeException(message))
                    eventListener.onError(message)
                }
            }
        }, cameraHandler)
    }

    fun initPreviewSession() {
        imageReader = ImageReader
            .newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
            .apply { setOnImageAvailableListener(this@CameraUtils, imageReaderHandler) }

        val surface = eventListener.provideTextureViewSurface()
        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
                addTarget(surface)
                addTarget(imageReader.surface)
            }

        camera.createCaptureSession(
            listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera ${camera.id} session configuration failed")
                }
            }, cameraHandler
        )
    }

    private var isProcessing = false
    private var bitmap: Bitmap? = null
    override fun onImageAvailable(reader: ImageReader?) {
        var image: Image? = null
        try {
            image = reader?.acquireLatestImage()
            image ?: return
            if (isProcessing) {
                image.close()
                return
            }
            isProcessing = true
            val rgbBytes = ImageUtils.convertYUVImageToARGB(image)
            bitmap = Bitmap.createBitmap(rgbBytes, previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888)
            processImage(bitmap)
        } catch (ex: Exception) {
            Log.e(TAG, "Exception while acquiring image. Skipped.")
            ex.printStackTrace()
        } finally {
            image?.close()
        }
    }

    private fun processImage(image: Bitmap?) {
        image ?: return
        compositeDisposable.add(
            imageProcessor.processImage(image, orientation)
                .subscribe(
                    { result ->
                        eventListener.onProcessingResult(result)
                        isProcessing = false
                    },
                    { throwable ->
                        Log.e(TAG, throwable.stackTraceToString())
                        isProcessing = false
                    }
                )
        )
    }

    private fun getSensorOrientation(cameraId: String) =
        cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)!!

    private fun getCameraId(): String? = cameraManager.run {
        cameraIdList.firstOrNull { id: String ->
            val characteristics = getCameraCharacteristics(id)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
        }
    }

    private fun getSmallestValidOutputSize(cameraId: String): Size {
        val minSize = max(
            min(DESIRED_PREVIEW_SIZE.width, DESIRED_PREVIEW_SIZE.height),
            MINIMAL_VALID_PREVIEW_SIZE
        )

        return cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceHolder::class.java)
            .filter { it.width >= minSize && it.height >= minSize }
            .sortedBy { it.width * it.height }
            .also { Log.i(TAG, TextUtils.join("\n", it)) }
            .first()
    }

    interface EventListener {
        fun onPreviewSizeSelected(size: Size)
        fun onError(text: String)
        fun provideTextureViewSurface(): Surface
        fun onProcessingResult(result: MutableList<Classifier.Recognition>)
    }

}
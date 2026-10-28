/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Blocking Camera2 helpers; every wait has a timeout so one bad probe can't hang the run. */
class Cam(private val manager: CameraManager) {
    private val thread = HandlerThread("probe-cam").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    var lastFailure: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun open(id: String): CameraDevice {
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: String? = null
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                error = "disconnected"
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                error = "error $code"
                camera.close()
                latch.countDown()
            }
        })
        check(latch.await(5, TimeUnit.SECONDS)) { "open $id timed out" }
        return device ?: error("open $id: $error")
    }

    inline fun <T> withDevice(id: String, block: (CameraDevice) -> T): T {
        val device = open(id)
        try {
            return block(device)
        } finally {
            device.close()
        }
    }

    private fun config(
        outputs: List<OutputConfiguration>,
        params: CaptureRequest?,
        input: InputConfiguration?,
        callback: CameraCaptureSession.StateCallback,
    ) = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback).apply {
        params?.let { sessionParameters = it }
        input?.let { inputConfiguration = it }
    }

    /** Returns the session, or null with [lastFailure] set when configuration fails. */
    fun session(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        params: CaptureRequest? = null,
        input: InputConfiguration? = null,
    ): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        lastFailure = null
        device.createCaptureSession(config(outputs, params, input, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                lastFailure = "onConfigureFailed"
                latch.countDown()
            }
        }))
        if (!latch.await(10, TimeUnit.SECONDS)) lastFailure = "configure timed out"
        return session
    }

    /** CameraDevice.isSessionConfigurationSupported (API 29); "n/a" when the HAL doesn't implement it. */
    fun supported(device: CameraDevice, outputs: List<OutputConfiguration>, params: CaptureRequest? = null): String {
        if (Build.VERSION.SDK_INT < 29) return "n/a (API ${Build.VERSION.SDK_INT})"
        return try {
            device.isSessionConfigurationSupported(
                config(outputs, params, null, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) = Unit
                    override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                }),
            ).toString()
        } catch (t: Throwable) {
            "n/a (${t.javaClass.simpleName})"
        }
    }

    fun reader(w: Int, h: Int, format: Int, max: Int = 4): Pair<ImageReader, LinkedBlockingQueue<Image>> {
        val queue = LinkedBlockingQueue<Image>()
        val reader = ImageReader.newInstance(w, h, format, max)
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.let { queue.offer(it) }
        }, handler)
        return reader to queue
    }

    /** A reader whose frames are dropped as they arrive; used as the preview/3A stream. */
    fun sink(w: Int, h: Int, format: Int): ImageReader =
        ImageReader.newInstance(w, h, format, 4).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        }

    class Results {
        val completed = LinkedBlockingQueue<TotalCaptureResult>()
        val failed = LinkedBlockingQueue<CaptureFailure>()
        val started = LinkedBlockingQueue<Long>()
    }

    fun callback(results: Results) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, ts: Long, frame: Long) {
            results.started.offer(System.nanoTime())
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            results.completed.offer(result)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            results.failed.offer(failure)
        }
    }

    fun close() = thread.quitSafely()

    companion object {
        fun vendorResults(result: CaptureResult): List<Pair<String, String>> =
            result.keys.filterNot { it.name.startsWith("android.") }.map {
                it.name to Report.fmt(runCatching { result.get(it) }.getOrElse { e -> "<${e.javaClass.simpleName}>" })
            }
    }
}

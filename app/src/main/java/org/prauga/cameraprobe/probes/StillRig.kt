/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Preview sink + full-size JPEG on one camera: the session most still probes need. */
class StillRig private constructor(
    private val p: ProbeContext,
    val device: CameraDevice,
    val session: CameraCaptureSession,
    private val preview: ImageReader,
    val jpeg: ImageReader,
    private val jpegQueue: LinkedBlockingQueue<Image>,
) : AutoCloseable {

    /** Repeating preview for [ms] so 3A settles; [extra] applies the same keys as the captures. */
    fun converge(ms: Long = 1500, extra: (CaptureRequest.Builder) -> Unit = {}) {
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(preview.surface)
        extra(b)
        session.setRepeatingRequest(b.build(), null, p.cam.handler)
        Thread.sleep(ms)
    }

    class Shot(val image: Image?, val result: TotalCaptureResult?, val failures: Int, val shutterMs: Long?, val imageMs: Long)

    fun shoot(extra: (CaptureRequest.Builder) -> Unit = {}): Shot {
        val results = org.prauga.cameraprobe.Cam.Results()
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(jpeg.surface)
        extra(b)
        jpegQueue.clear()
        val t0 = System.nanoTime()
        session.capture(b.build(), p.cam.callback(results), p.cam.handler)
        val started = results.started.poll(5, TimeUnit.SECONDS)
        val image = jpegQueue.poll(8, TimeUnit.SECONDS)
        val imageMs = (System.nanoTime() - t0) / 1_000_000
        val result = results.completed.poll(5, TimeUnit.SECONDS)
        return Shot(image, result, results.failed.size, started?.let { (it - t0) / 1_000_000 }, imageMs)
    }

    override fun close() {
        session.close()
        preview.close()
        jpeg.close()
        device.close()
    }

    companion object {
        /** Returns null (and logs why) when the session can't be configured. */
        fun open(p: ProbeContext, id: String, params: (CameraDevice) -> CaptureRequest? = { null }): StillRig? {
            val map = p.chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = Formats.largest(map, ImageFormat.JPEG) ?: run {
                p.r.line("[$id] no JPEG output")
                return null
            }
            val pv = Formats.preview(map)
            val device = p.cam.open(id)
            val preview = p.cam.sink(pv.width, pv.height, ImageFormat.YUV_420_888)
            val (jpeg, queue) = p.cam.reader(size.width, size.height, ImageFormat.JPEG, 3)
            val session = p.cam.session(
                device,
                listOf(OutputConfiguration(preview.surface), OutputConfiguration(jpeg.surface)),
                params(device),
            )
            if (session == null) {
                p.r.line("[$id] still session failed: ${p.cam.lastFailure}")
                preview.close()
                jpeg.close()
                device.close()
                return null
            }
            return StillRig(p, device, session, preview, jpeg, queue)
        }
    }
}

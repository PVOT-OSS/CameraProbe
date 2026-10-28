/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import org.prauga.cameraprobe.Cam
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * Lens switch map of every logical multi-camera: sweep CONTROL_ZOOM_RATIO and record the active
 * physical camera. Scene dependent (close/dark subjects keep some HALs on the ultrawide):
 * run it pointed at a lit scene a few metres away.
 */
object ZoomProbe : Probe {
    override val name = "zoom"
    private const val STEPS = 60

    override fun run(p: ProbeContext) {
        p.r.section(name)
        if (Build.VERSION.SDK_INT < 30) {
            p.r.line("skipped: CONTROL_ZOOM_RATIO needs API 30")
            return
        }
        for (id in p.physical.keys) p.r.guard("zoom $id") { sweep(p, id) }
        if (p.physical.isEmpty()) p.r.line("no logical multi-camera")
    }

    @TargetApi(30)
    private fun sweep(p: ProbeContext, id: String) {
        val c = p.chars(id)
        val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return
        val lo = ln(range.lower.toDouble())
        val hi = ln(range.upper.toDouble())
        val ratios = (List(STEPS + 1) { exp(lo + (hi - lo) * it / STEPS).toFloat() } + 1f).sorted().distinct()
        val pv = Formats.preview(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val preview = p.cam.sink(pv.width, pv.height, ImageFormat.YUV_420_888)
        try {
            p.cam.withDevice(id) { device ->
                val session = p.cam.session(device, listOf(OutputConfiguration(preview.surface))) ?: return@withDevice
                val switches = StringBuilder()
                var last: String? = null
                val results = Cam.Results()
                for (z in ratios) {
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(preview.surface)
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z.coerceIn(range.lower, range.upper))
                    results.completed.clear()
                    session.setRepeatingRequest(b.build(), p.cam.callback(results), p.cam.handler)
                    var phys: String? = null
                    var lux: String? = null
                    repeat(10) {
                        results.completed.poll(500, TimeUnit.MILLISECONDS)?.let { res ->
                            phys = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                            lux = "${res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1_000_000)}ms/iso${res.get(CaptureResult.SENSOR_SENSITIVITY)}" +
                                "/focus${res.get(CaptureResult.LENS_FOCUS_DISTANCE)}"
                        }
                    }
                    if (phys != last) {
                        switches.append("${"%.2f".format(z)}x->$phys ($lux)  ")
                        last = phys
                    }
                }
                p.r.line("[$id] range=$range physical=${p.physical[id]}: $switches")
                session.close()
            }
        } finally {
            preview.close()
        }
    }
}

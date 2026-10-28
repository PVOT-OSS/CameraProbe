/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import org.prauga.cameraprobe.Cam
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext
import org.prauga.cameraprobe.Report
import java.util.concurrent.TimeUnit

/**
 * Full-resolution RAW bursts with manual exposure: the input of any multi-frame merge.
 * Reports the sustained frame rate and which per-frame calibration metadata arrives.
 */
object BurstProbe : Probe {
    override val name = "burst"
    private const val FRAMES = 30

    override fun run(p: ProbeContext) {
        p.r.section(name)
        for (id in p.ids) {
            val caps = p.capabilities(id)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) {
                p.r.line("[$id] no RAW capability")
                continue
            }
            val map = p.chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            for (format in listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR)) {
                val size = Formats.largest(map, format) ?: continue
                p.r.guard("[$id] burst ${Formats.name(format)}") { burst(p, id, format, size.width, size.height, caps) }
            }
        }
    }

    private fun burst(p: ProbeContext, id: String, format: Int, w: Int, h: Int, caps: Set<Int>) {
        val r = p.r
        val c = p.chars(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val minFrame = map.getOutputMinFrameDuration(format, android.util.Size(w, h))
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.clamp(10_000_000L) ?: 10_000_000L
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.clamp(200) ?: 200
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val pv = Formats.preview(map)
        val preview = p.cam.sink(pv.width, pv.height, ImageFormat.YUV_420_888)
        val (raw, queue) = p.cam.reader(w, h, format, FRAMES + 2)
        try {
            p.cam.withDevice(id) { device ->
                val session = p.cam.session(device, listOf(OutputConfiguration(preview.surface), OutputConfiguration(raw.surface)))
                    ?: run {
                        r.line("[$id] ${Formats.name(format)}: configure failed ${p.cam.lastFailure}")
                        return@withDevice
                    }
                val pb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview.surface) }
                session.setRepeatingRequest(pb.build(), null, p.cam.handler)
                Thread.sleep(1000)
                session.stopRepeating()
                val requests = List(FRAMES) {
                    device.createCaptureRequest(if (manual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(raw.surface)
                        if (manual) {
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(minFrame, exposure))
                        }
                        set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                        set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CameraMetadata.STATISTICS_OIS_DATA_MODE_ON)
                    }.build()
                }
                val results = Cam.Results()
                val t0 = System.nanoTime()
                session.captureBurst(requests, p.cam.callback(results), p.cam.handler)
                var images = 0
                val deadline = System.nanoTime() + 10_000_000_000L
                while (images < FRAMES && System.nanoTime() < deadline) {
                    queue.poll(500, TimeUnit.MILLISECONDS)?.let { it.close(); images++ }
                }
                val wall = (System.nanoTime() - t0) / 1e6
                Thread.sleep(300)
                val res = generateSequence { results.completed.poll() }.toList()
                val gaps = res.mapNotNull { it.get(CaptureResult.SENSOR_TIMESTAMP) }.sorted().zipWithNext { a, b -> (b - a) / 1e6 }
                r.line(
                    "[$id] ${Formats.name(format)} ${w}x$h manual=$manual: images=$images/$FRAMES results=${res.size} failed=${results.failed.size} " +
                        "wall=${"%.0f".format(wall)}ms sensorFps=${if (gaps.isNotEmpty()) "%.1f".format(1000 / gaps.average()) else "?"} " +
                        "gap=${gaps.minOrNull()?.let { "%.1f".format(it) }}..${gaps.maxOrNull()?.let { "%.1f".format(it) }}ms minFrame=${minFrame / 1e6}ms",
                )
                res.firstOrNull()?.let { f ->
                    val black = if (Build.VERSION.SDK_INT >= 29) Report.fmt(f.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)) else "n/a"
                    val white = if (Build.VERSION.SDK_INT >= 29) f.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else "n/a"
                    r.line(
                        "  meta: exp=${f.get(CaptureResult.SENSOR_EXPOSURE_TIME)} iso=${f.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                            "noiseProfile=${f.get(CaptureResult.SENSOR_NOISE_PROFILE)?.size} " +
                            "shadingMap=${f.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { "${it.columnCount}x${it.rowCount}" }} " +
                            "black=$black white=$white ois=${f.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.size} " +
                            "neutral=${f.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.joinToString()} vendorKeys=${Cam.vendorResults(f).size}",
                    )
                }
                session.close()
            }
        } finally {
            raw.close()
            preview.close()
        }
    }
}

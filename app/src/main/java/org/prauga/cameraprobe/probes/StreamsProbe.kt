/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.MultiResolutionImageReader
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Which output combinations each camera accepts *and actually fills*, built from what it advertises:
 * every format at its largest size, max-resolution (quad-bayer) mode, each physical lens alone and
 * in pairs, PDAF/dual-pixel depth streams, vendor stream use cases, multi-resolution RAW.
 */
object StreamsProbe : Probe {
    override val name = "streams"

    class Out(
        val w: Int,
        val h: Int,
        val format: Int,
        val phys: String? = null,
        val useCase: Long? = null,
        val maxRes: Boolean = false,
    ) {
        override fun toString() = buildString {
            append("${Formats.name(format)} ${w}x$h")
            phys?.let { append(" @phys$it") }
            useCase?.let { append(" useCase=0x%x".format(it)) }
            if (maxRes) append(" maxRes")
        }
    }

    override fun run(p: ProbeContext) {
        p.r.section(name)
        for (id in p.ids) {
            for ((label, outs) in cases(p, id)) runCase(p, id, label, outs)
            if (Build.VERSION.SDK_INT >= 31) p.r.guard("multiRes $id") { multiRes(p, id) }
        }
    }

    private fun cases(p: ProbeContext, id: String): List<Pair<String, List<Out>>> {
        val c = p.chars(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val cases = mutableListOf<Pair<String, List<Out>>>()

        for ((format, fname, api) in Formats.OUTPUTS) {
            if (Build.VERSION.SDK_INT < api) continue
            Formats.largest(map, format)?.let { cases += "largest $fname" to listOf(Out(it.width, it.height, format)) }
        }
        val raw = Formats.largest(map, ImageFormat.RAW10)?.let { Out(it.width, it.height, ImageFormat.RAW10) }
            ?: Formats.largest(map, ImageFormat.RAW_SENSOR)?.let { Out(it.width, it.height, ImageFormat.RAW_SENSOR) }
        val jpeg = Formats.largest(map, ImageFormat.JPEG)?.let { Out(it.width, it.height, ImageFormat.JPEG) }
        if (raw != null && jpeg != null) cases += "jpeg + raw" to listOf(jpeg, raw)

        if (Build.VERSION.SDK_INT >= 31) {
            val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.JPEG, ImageFormat.YUV_420_888)) {
                Formats.largest(maxMap, format)?.let {
                    cases += "max-resolution ${Formats.name(format)}" to listOf(Out(it.width, it.height, format, maxRes = true))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val vendorUseCases = c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.filter { it >= 0x10000 }.orEmpty()
            val yuv = Formats.largest(map, ImageFormat.YUV_420_888)
            for (uc in vendorUseCases) {
                if (yuv != null) cases += "vendor use case 0x%x yuv".format(uc) to listOf(Out(yuv.width, yuv.height, ImageFormat.YUV_420_888, useCase = uc))
                if (jpeg != null) cases += "vendor use case 0x%x jpeg".format(uc) to listOf(Out(jpeg.w, jpeg.h, ImageFormat.JPEG, useCase = uc))
            }
        }

        val phys = p.physical[id].orEmpty()
        val physRaw = phys.mapNotNull { pid ->
            val pm = p.chars(pid).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            (Formats.largest(pm, ImageFormat.RAW10)?.let { Out(it.width, it.height, ImageFormat.RAW10, pid) }
                ?: Formats.largest(pm, ImageFormat.RAW_SENSOR)?.let { Out(it.width, it.height, ImageFormat.RAW_SENSOR, pid) })
        }
        physRaw.forEach { cases += "physical ${it.phys} raw" to listOf(it) }
        for (i in physRaw.indices) for (j in i + 1 until physRaw.size) {
            cases += "physical ${physRaw[i].phys}+${physRaw[j].phys} raw" to listOf(physRaw[i], physRaw[j])
        }
        for (pid in phys.ifEmpty { listOf(id) }) {
            depthStreams(p, pid).forEach { (format, w, h) ->
                cases += "pdaf/depth ${Formats.name(format)} ${w}x$h" + (if (phys.isEmpty()) "" else " @phys$pid") to
                    listOf(Out(w, h, format, pid.takeIf { phys.isNotEmpty() }))
            }
        }
        return cases
    }

    /**
     * RAW10/RAW16 entries of android.depth.availableDepthStreamConfigurations (format, w, h, isInput)
     * are dual-pixel/PDAF streams; the framework maps them to the hidden RAW_DEPTH10/RAW_DEPTH formats.
     */
    private fun depthStreams(p: ProbeContext, id: String): List<Triple<Int, Int, Int>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val key = CameraCharacteristics.Key("android.depth.availableDepthStreamConfigurations", IntArray::class.java)
        val raw = runCatching { p.chars(id).get(key) }.getOrNull() ?: return emptyList()
        return raw.toList().chunked(4).filter { it.size == 4 && it[3] == 0 }.mapNotNull { (format, w, h) ->
            when (format) {
                0x25 -> Triple(Formats.RAW_DEPTH10, w, h)
                0x20 -> Triple(Formats.RAW_DEPTH, w, h)
                else -> null
            }
        }.distinct()
    }

    private fun runCase(p: ProbeContext, id: String, label: String, outs: List<Out>) {
        val r = p.r
        r.guard("[$id] $label") {
            val readers = mutableListOf<Pair<ImageReader, LinkedBlockingQueue<Image>>>()
            val pv = Formats.preview(p.chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val preview = p.cam.sink(pv.width, pv.height, ImageFormat.YUV_420_888)
            try {
                p.cam.withDevice(id) { device ->
                    val configs = mutableListOf(OutputConfiguration(preview.surface))
                    for (o in outs) {
                        val rq = p.cam.reader(o.w, o.h, o.format, 3)
                        readers += rq
                        configs += OutputConfiguration(rq.first.surface).apply {
                            o.phys?.let { setPhysicalCameraId(it) }
                            if (Build.VERSION.SDK_INT >= 33) o.useCase?.let { streamUseCase = it }
                            if (Build.VERSION.SDK_INT >= 31 && o.maxRes) addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                        }
                    }
                    val sup = p.cam.supported(device, configs)
                    val session = p.cam.session(device, configs)
                    if (session == null) {
                        r.line("[$id] $label: supported=$sup configured=NO (${p.cam.lastFailure}) $outs")
                        return@withDevice
                    }
                    val results = org.prauga.cameraprobe.Cam.Results()
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    b.addTarget(preview.surface)
                    readers.forEach { b.addTarget(it.first.surface) }
                    if (Build.VERSION.SDK_INT >= 31 && outs.any { it.maxRes }) {
                        b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    }
                    session.capture(b.build(), p.cam.callback(results), p.cam.handler)
                    val got = readers.map { (_, q) -> q.poll(4, TimeUnit.SECONDS)?.also { it.close() } != null }
                    val res = results.completed.poll(3, TimeUnit.SECONDS)
                    val extra = buildString {
                        if (Build.VERSION.SDK_INT >= 29) append(" activePhys=${res?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}")
                        if (Build.VERSION.SDK_INT >= 31) append(" physResults=${res?.physicalCameraTotalResults?.keys}")
                    }
                    r.line("[$id] $label: supported=$sup configured=YES delivered=$got failed=${results.failed.map { it.reason }}$extra $outs")
                    session.close()
                }
            } finally {
                readers.forEach { it.first.close() }
                preview.close()
            }
        }
    }

    /** MultiResolutionImageReader (API 31): which lens delivers RAW as zoom moves across the range. */
    @TargetApi(31)
    private fun multiRes(p: ProbeContext, id: String) {
        val c = p.chars(id)
        val map = c.get(CameraCharacteristics.SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP) ?: return
        val format = listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR, ImageFormat.YUV_420_888)
            .firstOrNull { f -> runCatching { map.getOutputInfo(f).isNotEmpty() }.getOrDefault(false) } ?: return
        val infos = map.getOutputInfo(format)
        p.r.line("[$id] multiRes ${Formats.name(format)} infos=${infos.map { "${it.physicalCameraId}:${it.width}x${it.height}" }}")
        val range = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val zooms = range?.let { listOf(it.lower, 1f, (it.upper / 2).coerceAtLeast(1f), it.upper).distinct() } ?: listOf(1f)
        val mr = MultiResolutionImageReader(infos, format, 3)
        val arrivals = LinkedBlockingQueue<String>()
        mr.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.let { img ->
                arrivals.offer("${mr.getStreamInfoForImageReader(reader).physicalCameraId}:${img.width}x${img.height}")
                img.close()
            }
        }, p.cam.executor)
        val pv = Formats.preview(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val preview = p.cam.sink(pv.width, pv.height, ImageFormat.YUV_420_888)
        try {
            p.cam.withDevice(id) { device ->
                val configs = listOf(OutputConfiguration(preview.surface)) + OutputConfiguration.createInstancesForMultiResolutionOutput(mr)
                val session = p.cam.session(device, configs) ?: run {
                    p.r.line("[$id] multiRes: configure failed ${p.cam.lastFailure}")
                    return@withDevice
                }
                for (zoom in zooms) {
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(preview.surface)
                    if (range != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    session.setRepeatingRequest(b.build(), null, p.cam.handler)
                    Thread.sleep(800)
                    val s = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    s.addTarget(mr.surface)
                    if (range != null) s.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    arrivals.clear()
                    session.capture(s.build(), null, p.cam.handler)
                    p.r.line("[$id] multiRes zoom=$zoom -> ${arrivals.poll(3, TimeUnit.SECONDS)}")
                }
                session.close()
            }
        } finally {
            mr.close()
            preview.close()
        }
    }
}

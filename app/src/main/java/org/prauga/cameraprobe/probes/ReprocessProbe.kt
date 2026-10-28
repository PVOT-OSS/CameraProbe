/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext

/**
 * ZSL/reprocessing: only *advertised* input formats are configured. Unadvertised inputs are never
 * tried: a RAW10 input aborted the Pixel 9 camera provider (SIGABRT), any HAL may do the same.
 */
object ReprocessProbe : Probe {
    override val name = "reprocess"

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        for (id in p.ids) {
            val caps = p.capabilities(id)
            val map = p.chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val inputs = runCatching { map?.inputFormats?.toList() }.getOrNull().orEmpty()
            r.line(
                "[$id] privateReprocess=${CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in caps} " +
                    "yuvReprocess=${CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in caps} " +
                    "inputFormats=${inputs.map(Formats::name)}",
            )
            for (format in inputs) {
                val size = map?.getInputSizes(format)?.maxByOrNull { it.width * it.height } ?: continue
                r.guard("[$id] reprocess ${Formats.name(format)}") {
                    val jpeg = Formats.largest(map, ImageFormat.JPEG) ?: return@guard
                    val (out, _) = p.cam.reader(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
                    try {
                        p.cam.withDevice(id) { device ->
                            val s = p.cam.session(device, listOf(OutputConfiguration(out.surface)), input = InputConfiguration(size.width, size.height, format))
                            r.line("[$id] reprocess input ${Formats.name(format)} ${size}: ${if (s != null) "configured reprocessable=${s.isReprocessable}" else "failed ${p.cam.lastFailure}"}")
                            s?.close()
                        }
                    } finally {
                        out.close()
                    }
                }
            }
            r.guard("[$id] enable zsl") {
                StillRig.open(p, id)?.use { rig ->
                    val default = rig.device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).build()
                        .get(CaptureRequest.CONTROL_ENABLE_ZSL)
                    rig.converge()
                    val shot = rig.shoot { it.set(CaptureRequest.CONTROL_ENABLE_ZSL, true) }
                    shot.image?.close()
                    r.line(
                        "[$id] CONTROL_ENABLE_ZSL default=$default requested=true result=${shot.result?.get(CaptureResult.CONTROL_ENABLE_ZSL)} " +
                            "advertised=${CaptureRequest.CONTROL_ENABLE_ZSL in p.chars(id).availableCaptureRequestKeys}",
                    )
                }
            }
        }
    }
}

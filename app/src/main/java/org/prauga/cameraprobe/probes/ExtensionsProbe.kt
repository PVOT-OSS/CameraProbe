/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.graphics.ImageFormat
import android.os.Build
import org.prauga.cameraprobe.ProbeContext

/** OEM extensions (Night/HDR/Bokeh/...) exposed through Camera2 CameraExtensionCharacteristics. */
object ExtensionsProbe : Probe {
    override val name = "extensions"

    private fun extName(ext: Int) = when (ext) {
        0 -> "AUTOMATIC"
        1 -> "FACE_RETOUCH"
        2 -> "BOKEH"
        3 -> "HDR"
        4 -> "NIGHT"
        else -> "ext$ext"
    }

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        if (Build.VERSION.SDK_INT < 31) {
            r.line("skipped: Camera2 extensions need API 31 (device ${Build.VERSION.SDK_INT})")
            return
        }
        for (id in p.ids) {
            r.guard("extensions $id") {
                val e = p.manager.getCameraExtensionCharacteristics(id)
                val supported = e.supportedExtensions
                r.line("[$id] supported=${supported.map(::extName)}")
                for (ext in supported) {
                    val jpeg = e.getExtensionSupportedSizes(ext, ImageFormat.JPEG)
                    val yuv = e.getExtensionSupportedSizes(ext, ImageFormat.YUV_420_888)
                    val sb = StringBuilder("  ${extName(ext)}: jpeg=${jpeg.maxByOrNull { it.width * it.height }} (${jpeg.size}) yuv=${yuv.size}")
                    if (Build.VERSION.SDK_INT >= 33) {
                        sb.append(" reqKeys=${e.getAvailableCaptureRequestKeys(ext).map { it.name }}")
                        sb.append(" resKeys=${e.getAvailableCaptureResultKeys(ext).map { it.name }}")
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        sb.append(" postview=${e.isPostviewAvailable(ext)} progress=${e.isCaptureProcessProgressAvailable(ext)}")
                    }
                    r.line(sb.toString())
                }
            }
        }
    }
}

/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.graphics.ImageFormat
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size

object Formats {
    /** ImageFormat.RAW_DEPTH / RAW_DEPTH10 are @hide; dual-pixel/PDAF streams use them. */
    const val RAW_DEPTH = 0x1002
    const val RAW_DEPTH10 = 0x1003

    /** Output formats worth probing, with the API level that introduced each constant. */
    val OUTPUTS: List<Triple<Int, String, Int>> = listOf(
        Triple(ImageFormat.RAW10, "RAW10", 21),
        Triple(ImageFormat.RAW12, "RAW12", 23),
        Triple(ImageFormat.RAW_SENSOR, "RAW16", 21),
        Triple(ImageFormat.RAW_PRIVATE, "RAW_PRIVATE", 24),
        Triple(ImageFormat.YUV_420_888, "YUV", 21),
        Triple(ImageFormat.PRIVATE, "PRIVATE", 23),
        Triple(ImageFormat.JPEG, "JPEG", 21),
        Triple(ImageFormat.Y8, "Y8", 29),
        Triple(ImageFormat.HEIC, "HEIC", 29),
        Triple(ImageFormat.DEPTH16, "DEPTH16", 23),
        Triple(ImageFormat.DEPTH_JPEG, "DEPTH_JPEG", 29),
        Triple(ImageFormat.YCBCR_P010, "P010", 33),
        Triple(ImageFormat.JPEG_R, "JPEG_R", 34),
    )

    fun name(format: Int): String = OUTPUTS.firstOrNull { it.first == format }?.second
        ?: when (format) {
            RAW_DEPTH -> "RAW_DEPTH"
            RAW_DEPTH10 -> "RAW_DEPTH10"
            else -> "0x%x".format(format)
        }

    fun sizes(map: StreamConfigurationMap?, format: Int): List<Size> =
        runCatching { map?.getOutputSizes(format)?.toList() }.getOrNull().orEmpty()

    fun largest(map: StreamConfigurationMap?, format: Int): Size? = sizes(map, format).maxByOrNull { it.width.toLong() * it.height }

    /** A YUV size near 640x480 for the 3A/preview stream, which every probe session carries. */
    fun preview(map: StreamConfigurationMap?): Size =
        sizes(map, ImageFormat.YUV_420_888).minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
}

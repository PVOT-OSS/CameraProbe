/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import org.prauga.cameraprobe.ProbeContext
import org.prauga.cameraprobe.Report

/**
 * Every key list per logical/physical camera, every vendor characteristic value, and the HAL's
 * default value of every vendor request key per template (what the HAL would do untouched).
 */
object KeysProbe : Probe {
    override val name = "keys"

    private val TEMPLATES = listOf(
        CameraDevice.TEMPLATE_PREVIEW to "PREVIEW",
        CameraDevice.TEMPLATE_STILL_CAPTURE to "STILL_CAPTURE",
        CameraDevice.TEMPLATE_RECORD to "RECORD",
        CameraDevice.TEMPLATE_VIDEO_SNAPSHOT to "VIDEO_SNAPSHOT",
        CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG to "ZERO_SHUTTER_LAG",
        CameraDevice.TEMPLATE_MANUAL to "MANUAL",
    )

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        r.line("vendor key names across all cameras: ${p.vendorNames.size}")
        r.file("vendor-keys.txt", p.vendorNames.joinToString("\n"))
        for (id in p.allIds) {
            r.guard("keys $id") {
                val c = p.chars(id)
                val sb = StringBuilder()
                fun dump(title: String, names: List<String>) {
                    val vendor = names.count { !it.startsWith("android.") }
                    r.line("  [$id] $title: ${names.size} ($vendor vendor)")
                    sb.append("## $title (${names.size}, vendor $vendor)\n")
                    names.sorted().forEach { sb.append(it).append('\n') }
                }
                dump("characteristics", c.keys.map { it.name })
                dump("request", c.availableCaptureRequestKeys.map { it.name })
                dump("result", c.availableCaptureResultKeys.map { it.name })
                dump("session", c.availableSessionKeys.map { it.name })
                dump("physicalRequest", c.availablePhysicalCameraRequestKeys?.map { it.name }.orEmpty())
                sb.append("## vendor characteristic values\n")
                c.keys.filterNot { it.name.startsWith("android.") }.forEach {
                    sb.append(it.name).append(" = ").append(Report.fmt(runCatching { c.get(it) }.getOrNull())).append('\n')
                }
                if (id in p.ids) sb.append(templateDefaults(p, id))
                r.file("keys-$id.txt", sb.toString())
            }
        }
    }

    private fun templateDefaults(p: ProbeContext, id: String): String {
        val c = p.chars(id)
        val vendorKeys = c.availableCaptureRequestKeys.filterNot { it.name.startsWith("android.") }
        if (vendorKeys.isEmpty()) return ""
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in p.capabilities(id)
        val sb = StringBuilder("## vendor request defaults per template\n")
        p.cam.withDevice(id) { device ->
            for ((template, label) in TEMPLATES) {
                if (template == CameraDevice.TEMPLATE_MANUAL && !manual) continue
                val request = runCatching { device.createCaptureRequest(template).build() }.getOrElse {
                    sb.append("[$label] unsupported: ${it.message}\n")
                    null
                } ?: continue
                for (key in vendorKeys) {
                    sb.append("[$label] ").append(key.name).append(" = ")
                        .append(Report.fmt(runCatching { request.get(key) }.getOrElse { "<${it.javaClass.simpleName}>" })).append('\n')
                }
            }
        }
        return sb.toString()
    }
}

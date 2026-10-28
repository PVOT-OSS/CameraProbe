/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import org.prauga.cameraprobe.ProbeContext

/** Who the device is, which camera props it sets, which cameras exist and which profiles apply. */
object EnvProbe : Probe {
    override val name = "env"

    private val PROP_FILTER = Regex("camera|lyric|gcam|isp|soc|board|hardware|platform|chipname|vendor\\.build|product\\.(model|device|brand|manufacturer)", RegexOption.IGNORE_CASE)

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        r.line("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} device=${Build.DEVICE} product=${Build.PRODUCT}")
        r.line("hardware=${Build.HARDWARE} board=${Build.BOARD} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} patch=${Build.VERSION.SECURITY_PATCH}")
        if (Build.VERSION.SDK_INT >= 31) r.line("soc=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        r.line("fingerprint=${Build.FINGERPRINT}")

        r.guard("getprop") {
            val props = Runtime.getRuntime().exec(arrayOf("getprop")).inputStream.bufferedReader().readLines()
            val camera = props.filter { PROP_FILTER.containsMatchIn(it) }
            r.line("getprop: ${props.size} readable, ${camera.size} camera/platform related")
            r.file("props.txt", camera.joinToString("\n"))
        }

        r.line("cameraIdList=${p.ids} physical=${p.physical}")
        if (Build.VERSION.SDK_INT >= 30) r.guard("concurrent") { r.line("concurrentCameraIds=${p.manager.concurrentCameraIds}") }
        for (id in p.allIds) {
            r.guard("camera $id") {
                val c = p.chars(id)
                r.line(
                    "  [$id] facing=${p.facing(id)} level=${c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)} " +
                        "caps=${p.capabilities(id).sorted()} pixelArray=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} " +
                        "focal=${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()}",
                )
            }
        }

        val prefixes = p.vendorNames.map { it.split('.').take(3).joinToString(".") }.groupingBy { it }.eachCount()
        r.line("vendor key families: $prefixes")
        r.line("profiles: ${p.profiles.map { it.name }} active=${p.activeProfiles.map { it.name }}")
        p.activeProfiles.forEach { r.line("  profile ${it.name}: halLogTags=${it.logTags} ${it.notes}") }
        if (p.activeProfiles.isEmpty()) r.line("  no profile matches: vendor probes stay generic (see keys-*.txt to write one)")
    }
}

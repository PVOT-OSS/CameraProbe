/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.annotation.TargetApi
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import org.prauga.cameraprobe.Cam
import org.prauga.cameraprobe.ProbeContext
import org.prauga.cameraprobe.Report
import org.prauga.cameraprobe.profile.Profile

/**
 * Profile-driven vendor tag probing on the main camera:
 * - A/B flags: capture without and with the tag (e.g. Pixel IsCameraApp1P), diff the vendor results,
 * - request experiments: does the HAL accept/echo a tag, does the output change,
 * - result probes: unadvertised result tags the framework may still pass through.
 * Without a matching profile it only records the generic vendor results of a plain capture.
 */
object VendorProbe : Probe {
    override val name = "vendor"

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        val id = p.mainId
        r.line("main camera=$id profiles=${p.activeProfiles.map { it.name }}")
        if (Build.VERSION.SDK_INT < 29) {
            r.line("vendor keys by name need API 29 (device ${Build.VERSION.SDK_INT}); generic capture only")
            capture(p, id, "generic", emptyList(), null)
            return
        }
        if (p.activeProfiles.isEmpty()) {
            capture(p, id, "generic", emptyList(), null)
            return
        }
        for (profile in p.activeProfiles) {
            capture(p, id, "${profile.name}-base", profile.resultProbes, null, experiments = profile.experiments)
            for (flag in profile.abFlags) {
                capture(p, id, "${profile.name}-${flag.label}", profile.resultProbes, flag)
            }
        }
    }

    /** False when the HAL's vendor tag table has no such tag (the framework rejects the name). */
    @TargetApi(29)
    private fun exists(device: CameraDevice, tag: Profile.Tag): Boolean = runCatching {
        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).set(tag.requestKey, tag.value)
    }.isSuccess

    private fun capture(
        p: ProbeContext,
        id: String,
        label: String,
        probes: List<Profile.Tag>,
        flag: Profile.Tag?,
        experiments: List<Profile.Tag> = emptyList(),
    ) {
        val r = p.r
        r.guard("vendor $label") {
            val set: (CaptureRequest.Builder) -> Unit = { b -> flag?.let { b.set(it.requestKey, it.value) } }
            r.mark("vendor-$label configure")
            val rig = StillRig.open(p, id) { device ->
                flag?.let {
                    check(exists(device, it)) { "tag ${it.tag} not in this HAL" }
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply(set).build()
                }
            } ?: return@guard
            rig.use {
                rig.converge { if (flag?.perRequest != false) set(it) }
                r.mark("vendor-$label capture")
                val shot = rig.shoot { if (flag?.perRequest != false) set(it) }
                r.line("[$label] jpeg=${shot.image != null} shutter=${shot.shutterMs}ms total=${shot.imageMs}ms failed=${shot.failures}")
                shot.image?.let { img ->
                    val buf = img.planes[0].buffer
                    r.bytes("jpeg-$label.jpg", ByteArray(buf.remaining()).also { buf.get(it) })
                    img.close()
                }
                shot.result?.let { result ->
                    val vendor = Cam.vendorResults(result)
                    val probed = probes.map { it.tag to Report.fmt(runCatching { result.get(it.resultKey) }.getOrElse { e -> "<${e.javaClass.simpleName}>" }) }
                    r.line("[$label] vendor result keys=${vendor.size}; probes non-null=${probed.count { it.second != "null" && !it.second.startsWith("<") }}/${probed.size}")
                    r.line(
                        "[$label] exp=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)}" +
                            if (Build.VERSION.SDK_INT >= 29) " physId=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}" else "",
                    )
                    r.file(
                        "vendor-results-$label.txt",
                        (vendor + listOf("--- probes" to "") + probed).joinToString("\n") { "${it.first} = ${it.second}" },
                    )
                }
                for (exp in experiments) experiment(p, rig, exp)
            }
        }
    }

    @TargetApi(29)
    private fun experiment(p: ProbeContext, rig: StillRig, exp: Profile.Tag) {
        val r = p.r
        r.guard("experiment ${exp.label}") {
            if (!exists(rig.device, exp)) {
                r.line("  exp ${exp.label}: tag not in this HAL")
                return@guard
            }
            val shot = rig.shoot { it.set(exp.requestKey, exp.value) }
            val echo = shot.result?.keys?.firstOrNull { it.name == exp.tag }?.let { Report.fmt(shot.result.get(it)) } ?: "not in result"
            r.line("  exp ${exp.label}: image=${shot.image != null} bytes=${shot.image?.planes?.get(0)?.buffer?.remaining()} failed=${shot.failures} echo=$echo")
            shot.image?.close()
        }
    }
}

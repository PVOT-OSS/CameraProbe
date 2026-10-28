/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.hardware.camera2.CaptureResult
import org.prauga.cameraprobe.ProbeContext

/** Real full-size JPEG latency per camera, to compare against the advertised stall durations. */
object JpegProbe : Probe {
    override val name = "jpeg"

    override fun run(p: ProbeContext) {
        p.r.section(name)
        for (id in p.ids) {
            p.r.guard("jpeg $id") {
                StillRig.open(p, id)?.use { rig ->
                    rig.converge()
                    repeat(3) { i ->
                        val shot = rig.shoot()
                        p.r.line(
                            "[$id] shot $i: shutter=${shot.shutterMs}ms jpeg=${shot.imageMs}ms " +
                                "bytes=${shot.image?.planes?.get(0)?.buffer?.remaining()} " +
                                "exp=${shot.result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)} failed=${shot.failures}",
                        )
                        shot.image?.close()
                    }
                }
            }
        }
    }
}

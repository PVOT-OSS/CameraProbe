/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import org.prauga.cameraprobe.ProbeContext

interface Probe {
    val name: String
    fun run(p: ProbeContext)

    companion object {
        /** Ordered cheapest/safest first; select a subset with `--es only a,b`. */
        val all: List<Probe> = listOf(
            EnvProbe,
            KeysProbe,
            ExtensionsProbe,
            LibrariesProbe,
            VendorProbe,
            StreamsProbe,
            BurstProbe,
            ZoomProbe,
            JpegProbe,
            ReprocessProbe,
        )
    }
}

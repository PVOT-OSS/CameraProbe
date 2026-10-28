/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import org.prauga.cameraprobe.profile.Profile

class ProbeContext(val context: Context, val r: Report) {
    val manager: CameraManager = context.getSystemService(CameraManager::class.java)
    val cam = Cam(manager)

    val ids: List<String> = manager.cameraIdList.toList()

    private val charsCache = mutableMapOf<String, CameraCharacteristics>()
    fun chars(id: String): CameraCharacteristics = charsCache.getOrPut(id) { manager.getCameraCharacteristics(id) }

    /** Logical id -> physical ids, for logical multi-cameras only. */
    val physical: Map<String, List<String>> by lazy {
        ids.associateWith { chars(it).physicalCameraIds.toList() }.filterValues { it.size > 1 }
    }

    val allIds: List<String> by lazy { (ids + physical.values.flatten()).distinct() }

    fun capabilities(id: String): Set<Int> =
        chars(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()

    fun facing(id: String): String = when (chars(id).get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_BACK -> "back"
        CameraMetadata.LENS_FACING_FRONT -> "front"
        CameraMetadata.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    /** The camera the A/B probes use: the first back camera, else the first camera. */
    val mainId: String by lazy { ids.firstOrNull { facing(it) == "back" } ?: ids.first() }

    /** Every vendor key name the framework exposes on any camera (characteristics, request, result, session). */
    val vendorNames: Set<String> by lazy {
        allIds.flatMap { id ->
            val c = chars(id)
            c.keys.map { it.name } + c.availableCaptureRequestKeys.map { it.name } +
                c.availableCaptureResultKeys.map { it.name } + c.availableSessionKeys.map { it.name }
        }.filterNot { it.startsWith("android.") }.toSortedSet()
    }

    val profiles: List<Profile> by lazy { Profile.loadAll(context) }

    val activeProfiles: List<Profile> by lazy { profiles.filter { it.matches(vendorNames) } }

    fun close() = cam.close()
}

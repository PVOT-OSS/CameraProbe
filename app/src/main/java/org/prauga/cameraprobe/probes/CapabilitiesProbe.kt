/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.prauga.cameraprobe.Formats
import org.prauga.cameraprobe.ProbeContext
import org.prauga.cameraprobe.Report

/**
 * What every logical and physical camera can do, from the characteristics alone (no session is
 * opened, so it is safe on any device). Also rates each camera for multi-frame processing:
 * tier A = RAW + manual + full-res burst with calibration metadata, B = full-res YUV burst,
 * C = single frame. Writes capabilities.json for comparing devices.
 */
object CapabilitiesProbe : Probe {
    override val name = "capabilities"
    override val summary = "What each camera supports, and its processing tier"
    override val opensCamera = false

    private val CAPABILITIES = listOf(
        "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING",
        "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT",
        "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME",
        "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR",
        "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES",
    )

    private fun capabilityName(cap: Int) = CAPABILITIES.getOrNull(cap) ?: "cap$cap"

    private fun levelName(level: Int?) = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        4 -> "EXTERNAL"
        else -> "unknown($level)"
    }

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        val cameras = JSONArray()
        for (id in p.allIds) {
            r.guard("capabilities $id") {
                val camera = describe(p, id)
                cameras.put(camera)
                r.line(
                    "[$id] ${camera.getString("facing")} ${camera.getString("level")} tier=${camera.getString("tier")}" +
                        camera.optJSONArray("tierMissing")?.takeIf { it.length() > 0 }?.let { " missing=$it" }.orEmpty(),
                )
                r.line("  caps=${camera.getJSONArray("capabilities")}")
                r.line(
                    "  raw=${camera.opt("rawMax")}@${camera.opt("rawMaxFps")}fps yuv=${camera.opt("yuvMax")}@${camera.opt("yuvMaxFps")}fps " +
                        "iso=${camera.opt("isoRange")} exposureNs=${camera.opt("exposureRangeNs")} zoom=${camera.opt("zoomRatioRange")}",
                )
            }
        }
        r.file("capabilities.json", JSONObject().put("cameras", cameras).toString(2))
    }

    private fun describe(p: ProbeContext, id: String): JSONObject {
        val c = p.chars(id)
        val caps = p.capabilities(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val resultKeys = c.availableCaptureResultKeys.map { it.name }.toSet()
        val o = JSONObject()
            .put("id", id)
            .put("logicalParent", p.physical.entries.firstOrNull { id in it.value }?.key ?: JSONObject.NULL)
            .put("facing", p.facing(id))
            .put("level", levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
            .put("capabilities", JSONArray(caps.sorted().map(::capabilityName)))

        fun put(key: String, value: Any?) {
            if (value != null) o.put(key, json(value))
        }
        put("pixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE))
        put("activeArray", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
        put("sensorSizeMm", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
        put("colorFilter", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
        put("whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
        put("isoRange", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
        put("maxAnalogIso", c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY))
        put("exposureRangeNs", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
        put("maxFrameDurationNs", c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION))
        put("timestampSource", when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            else -> null
        })
        put("focalLengths", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
        put("apertures", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES))
        put("minFocusDistance", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
        put("oisModes", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))
        put("videoStabilizationModes", c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))
        put("afModes", c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))
        put("aeModes", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES))
        put("awbModes", c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES))
        put("sceneModes", c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES))
        put("aeFpsRanges", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
        put("aeLock", c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))
        put("noiseReductionModes", c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES))
        put("edgeModes", c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES))
        put("tonemapModes", c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES))
        put("distortionCorrectionModes", c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES))
        put("faceDetectModes", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES))
        put("lensShadingMapModes", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES))
        put("oisDataModes", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES))
        put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
        put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        put("lensPose", c.get(CameraCharacteristics.LENS_POSE_TRANSLATION) != null)
        put("lensIntrinsics", c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null)
        put("noiseProfileResult", CaptureResult.SENSOR_NOISE_PROFILE.name in resultKeys)
        put("shadingMapResult", CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP.name in resultKeys)
        if (Build.VERSION.SDK_INT >= 30) {
            put("zoomRatioRange", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE))
            put("extendedSceneModes", c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.map {
                "${it.mode}:${it.maxStreamingSize}:${it.zoomRatioRange}"
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            put("maxResolutionPixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            put("dynamicRangeProfiles", c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles)
            put("streamUseCases", c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            put("settingsOverrides", c.get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES))
            put("autoframing", c.get(CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE))
            put("colorSpaceProfiles", c.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)?.getSupportedColorSpaces(ImageFormat.UNKNOWN))
        }
        if (Build.VERSION.SDK_INT >= 35) {
            put("torchStrengthMax", c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL))
        }

        val formats = JSONObject()
        for ((format, label, api) in Formats.OUTPUTS) {
            if (Build.VERSION.SDK_INT < api) continue
            val size = Formats.largest(map, format) ?: continue
            formats.put(label, "$size@${fps(map?.getOutputMinFrameDuration(format, size))}fps")
        }
        o.put("formats", formats)
        put("highSpeedVideo", runCatching {
            map?.highSpeedVideoSizes?.map { size -> "$size:${map.getHighSpeedVideoFpsRangesFor(size).map { it.upper }.distinct()}" }
        }.getOrNull())

        val raw = Formats.largest(map, ImageFormat.RAW_SENSOR)
        val yuv = Formats.largest(map, ImageFormat.YUV_420_888)
        val rawFps = raw?.let { fps(map?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, it)) }
        val yuvFps = yuv?.let { fps(map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it)) }
        put("rawMax", raw)
        put("rawMaxFps", rawFps)
        put("yuvMax", yuv)
        put("yuvMaxFps", yuvFps)

        val missing = mutableListOf<String>()
        fun need(ok: Boolean, what: String) {
            if (!ok) missing.add(what)
        }
        need(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps, "RAW")
        need(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps, "MANUAL_SENSOR")
        need(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS in caps, "READ_SENSOR_SETTINGS")
        need(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in caps, "BURST_CAPTURE")
        need((rawFps ?: 0.0) >= 20, "RAW >= 20fps")
        need(CaptureResult.SENSOR_NOISE_PROFILE.name in resultKeys, "noise profile")
        need(c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
            ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true, "shading map")
        need(c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, "REALTIME timestamps")
        val yuvBurst = (yuvFps ?: 0.0) >= 10 && (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps ||
            c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true)
        o.put("tier", if (missing.isEmpty()) "A" else if (yuvBurst) "B" else "C")
        o.put("tierMissing", JSONArray(missing))
        return o
    }

    private fun fps(minFrameDurationNs: Long?): Double? =
        minFrameDurationNs?.takeIf { it > 0 }?.let { Math.round(1e10 / it) / 10.0 }

    private fun json(value: Any): Any = when (value) {
        is Boolean, is Number, is String -> value
        is Collection<*> -> JSONArray(value.map { it?.let(::json) ?: JSONObject.NULL })
        is IntArray -> JSONArray(value.toList())
        is FloatArray -> JSONArray(value.map { it.toDouble() })
        is LongArray -> JSONArray(value.toList())
        is BooleanArray -> JSONArray(value.toList())
        is Array<*> -> JSONArray(value.map { it?.let(::json) ?: JSONObject.NULL })
        else -> Report.fmt(value)
    }
}

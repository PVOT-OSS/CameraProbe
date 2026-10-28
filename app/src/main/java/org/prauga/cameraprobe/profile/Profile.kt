/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.profile

import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vendor knowledge for one OEM/HAL family, loaded from assets/profiles/<name>.json.
 * A profile is active when any vendor key on the device starts with one of its [prefixes].
 */
class Profile(json: JSONObject) {
    val name: String = json.getString("name")
    val prefixes: List<String> = json.getJSONArray("tagPrefixes").strings()
    val logTags: List<String> = json.optJSONArray("halLogTags")?.strings().orEmpty()
    val notes: String = json.optString("notes")

    /** Session-level flags compared A/B: one capture without, one with the tag set. */
    val abFlags: List<Tag> = json.optJSONArray("abFlags").tags(prefixes)

    /** Single-key request experiments run inside a plain still session. */
    val experiments: List<Tag> = json.optJSONArray("requestExperiments").tags(prefixes)

    /** Result tags read from every capture even when they're not advertised. */
    val resultProbes: List<Tag> = json.optJSONArray("resultProbes").tags(prefixes)

    fun matches(vendorNames: Collection<String>) = vendorNames.any { n -> prefixes.any { n.startsWith(it) } }

    class Tag(json: JSONObject, prefixes: List<String>) {
        val tag: String = json.getString("tag")
        val type: String = json.getString("type")
        val value: Any? = if (json.has("value")) convert(type, json.get("value")) else null
        val label: String = json.optString("label", shortName(tag, prefixes) + if (value != null) "=${json.get("value")}" else "")
        val perRequest: Boolean = json.optBoolean("perRequest", true)

        @get:TargetApi(29)
        @Suppress("UNCHECKED_CAST")
        val requestKey: CaptureRequest.Key<Any> get() = CaptureRequest.Key(tag, javaType(type) as Class<Any>)

        @get:TargetApi(29)
        @Suppress("UNCHECKED_CAST")
        val resultKey: CaptureResult.Key<Any> get() = CaptureResult.Key(tag, javaType(type) as Class<Any>)
    }

    companion object {
        fun loadAll(context: Context): List<Profile> =
            context.assets.list("profiles").orEmpty().filter { it.endsWith(".json") }.map { file ->
                Profile(JSONObject(context.assets.open("profiles/$file").bufferedReader().use { it.readText() }))
            }

        /** Vendor tag types as in the HAL vendor tag table: byte, int32, int64, float, double, rational. */
        fun javaType(type: String): Class<*> = when (type) {
            "byte" -> Byte::class.javaObjectType
            "boolean" -> Boolean::class.javaObjectType
            "int" -> Int::class.javaObjectType
            "long" -> Long::class.javaObjectType
            "float" -> Float::class.javaObjectType
            "double" -> Double::class.javaObjectType
            "byte[]" -> ByteArray::class.java
            "int[]" -> IntArray::class.java
            "long[]" -> LongArray::class.java
            "float[]" -> FloatArray::class.java
            "double[]" -> DoubleArray::class.java
            else -> error("unknown tag type $type")
        }

        private fun convert(type: String, v: Any): Any = when (type) {
            "byte" -> (v as Number).toByte()
            "boolean" -> v as Boolean
            "int" -> (v as Number).toInt()
            "long" -> (v as Number).toLong()
            "float" -> (v as Number).toFloat()
            "double" -> (v as Number).toDouble()
            "byte[]" -> (v as JSONArray).let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } }
            "int[]" -> (v as JSONArray).let { a -> IntArray(a.length()) { a.getInt(it) } }
            "long[]" -> (v as JSONArray).let { a -> LongArray(a.length()) { a.getLong(it) } }
            "float[]" -> (v as JSONArray).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            "double[]" -> (v as JSONArray).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            else -> error("unknown tag type $type")
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }

        private fun JSONArray?.tags(prefixes: List<String>) =
            this?.let { a -> List(a.length()) { Tag(a.getJSONObject(it), prefixes) } }.orEmpty()

        /** "com.google.pixel.experimental2019.MeshWarp.Mode" -> "MeshWarp.Mode" (prefix + year/section dropped). */
        private fun shortName(tag: String, prefixes: List<String>): String {
            val rest = prefixes.filter { tag.startsWith(it) }.maxByOrNull { it.length }?.let { tag.removePrefix(it) } ?: return tag
            return if (rest.contains('.') && rest.substringBefore('.').let { s -> s.isEmpty() || s.first().isDigit() }) rest.substringAfter('.') else rest
        }
    }
}

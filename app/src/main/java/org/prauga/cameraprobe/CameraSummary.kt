/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import org.json.JSONObject
import java.util.Locale
import java.io.File

/** One line per camera from capabilities.json: facing, level, processing tier, RAW and zoom. */
object CameraSummary {
    fun of(dir: File): String? {
        val file = File(dir, "capabilities.json").takeIf { it.exists() } ?: return null
        val cameras = runCatching { JSONObject(file.readText()).getJSONArray("cameras") }.getOrNull() ?: return null
        return (0 until cameras.length()).joinToString("\n") { i ->
            val c = cameras.getJSONObject(i)
            buildString {
                append(if (c.isNull("logicalParent")) "" else "  ↳ ")
                append(c.getString("id")).append("  ").append(c.getString("facing"))
                append(" · ").append(c.getString("level"))
                append(" · tier ").append(c.getString("tier"))
                c.optString("rawMax").takeIf { it.isNotEmpty() }?.let { append(" · RAW ").append(it) }
                zoom(c.optString("zoomRatioRange"))?.let { append(" · ").append(it) }
            }
        }
    }

    /** "[0.5557734, 20.0]" -> "0.56–20×"; a fixed 1× range is left out. */
    private fun zoom(range: String): String? {
        val (min, max) = Regex("[0-9.]+").findAll(range).map { it.value.toDouble() }.toList().takeIf { it.size == 2 } ?: return null
        if (min == max) return null
        fun f(v: Double) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
        return "${f(min)}–${f(max)}×"
    }
}

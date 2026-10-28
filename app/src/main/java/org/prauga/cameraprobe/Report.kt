/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.util.Log
import java.io.File

class Report(val dir: File, private val onLine: (String) -> Unit) {
    private val main = File(dir, "report.txt").apply { writeText("") }

    fun section(title: String) = line("\n== $title ==")

    fun line(text: String) {
        Log.i(TAG, text)
        main.appendText(text + "\n")
        onLine(text)
    }

    fun file(name: String, text: String) {
        File(dir, name).writeText(text)
        line("  -> wrote $name (${text.length} chars)")
    }

    fun bytes(name: String, data: ByteArray) {
        File(dir, name).writeBytes(data)
        line("  -> wrote $name (${data.size} bytes)")
    }

    /** Lines starting with PROBE_MARK let the host script align the HAL log with the probe. */
    fun mark(text: String) = line("PROBE_MARK $text t=${System.currentTimeMillis()}")

    inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            line("  !! $what failed: ${t.javaClass.simpleName}: ${t.message}")
            Log.w(TAG, what, t)
        }
    }

    companion object {
        const val TAG = "PraugaProbe"

        fun fmt(value: Any?): String = when (value) {
            null -> "null"
            is ByteArray -> value.joinToString(",", "[", "]", limit = 32)
            is IntArray -> value.joinToString(",", "[", "]", limit = 32)
            is LongArray -> value.joinToString(",", "[", "]", limit = 32)
            is FloatArray -> value.joinToString(",", "[", "]", limit = 32)
            is DoubleArray -> value.joinToString(",", "[", "]", limit = 32)
            is BooleanArray -> value.joinToString(",", "[", "]", limit = 32)
            is Array<*> -> value.joinToString(",", "[", "]", limit = 32) { fmt(it) }
            else -> value.toString()
        }
    }
}

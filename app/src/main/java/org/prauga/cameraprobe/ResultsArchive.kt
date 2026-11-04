/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs a run into one zip, laid out like scripts/run.sh results. Captured photos stay out. */
object ResultsArchive {
    private val PRIVATE_EXTENSIONS = setOf("jpg", "jpeg", "dng")

    /** cameraprobe-<manufacturer>-<device>-<buildId>-<timestamp>, from the run's run.json. */
    fun baseName(dir: File): String {
        val info = runCatching { JSONObject(File(dir, "run.json").readText()) }.getOrNull()
        val device = listOf("manufacturer", "device", "buildId")
            .mapNotNull { info?.optString(it)?.takeIf(String::isNotEmpty) }
            .joinToString("-")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "_")
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(File(dir, "run.json").lastModified()))
        return listOf("cameraprobe", device, time).filter { it.isNotEmpty() }.joinToString("-")
    }

    fun write(dir: File, out: OutputStream) {
        val root = baseName(dir)
        ZipOutputStream(out).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) !in PRIVATE_EXTENSIONS }
                .sortedBy { it.path }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry("$root/${file.relativeTo(dir).path}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}

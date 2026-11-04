/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.prauga.cameraprobe.probes.Probe
import java.io.File

/**
 * The current probe run, kept outside the activity so it survives the screen being recreated.
 * Everything is written to /sdcard/Android/data/org.prauga.cameraprobe/files/latest/
 * (pulled by scripts/run.sh, exported or shared from the app).
 */
object ProbeRun {
    interface Listener {
        fun onLine(line: String)
        fun onFinished()
    }

    var listener: Listener? = null

    @Volatile
    var running = false
        private set

    private val lines = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun resultsDir(context: Context) = File(context.getExternalFilesDir(null), "latest")

    /** Lines of the current or last run; after a process restart they come from report.txt. */
    fun lines(context: Context): List<String> = synchronized(lines) {
        if (lines.isEmpty()) {
            File(resultsDir(context), "report.txt").takeIf { it.exists() }?.readLines()?.let(lines::addAll)
        }
        lines.toList()
    }

    fun hasResults(context: Context) = !running && File(resultsDir(context), "run.json").exists()

    fun start(context: Context, probes: List<Probe>) {
        if (running) return
        running = true
        synchronized(lines) { lines.clear() }
        val app = context.applicationContext
        val dir = resultsDir(app).apply {
            deleteRecursively()
            mkdirs()
        }
        val report = Report(dir) { line ->
            synchronized(lines) { lines.add(line) }
            main.post { listener?.onLine(line) }
        }
        Thread {
            val p = ProbeContext(app, report)
            writeRunInfo(dir, p, probes)
            for (probe in probes) {
                report.guard("probe ${probe.name}") { probe.run(p) }
            }
            p.close()
            report.line("DONE")
            main.post {
                running = false
                listener?.onFinished()
            }
        }.start()
    }

    /** Machine-readable run identity: the host script names the result folder from it. */
    private fun writeRunInfo(dir: File, p: ProbeContext, probes: List<Probe>) {
        val info = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("buildId", Build.ID)
            .put("incremental", Build.VERSION.INCREMENTAL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("probes", probes.joinToString(",") { it.name })
            .put("halLogTags", runCatching { p.activeProfiles.flatMap { it.logTags }.distinct().joinToString(",") }.getOrDefault(""))
        if (Build.VERSION.SDK_INT >= 31) info.put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        File(dir, "run.json").writeText(info.toString(2))
    }
}

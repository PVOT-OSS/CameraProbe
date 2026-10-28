/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.prauga.cameraprobe.probes.Probe
import java.io.File

/**
 * Runs the probes on launch and writes everything to
 * /sdcard/Android/data/org.prauga.cameraprobe/files/latest/ (pulled by scripts/run.sh).
 * `am start -n org.prauga.cameraprobe/.ProbeActivity --es only env,vendor,zoom`
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val text = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)

        val dir = File(getExternalFilesDir(null), "latest").apply {
            deleteRecursively()
            mkdirs()
        }
        val report = Report(dir) { line ->
            runOnUiThread {
                text.append(line + "\n")
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            report.line("CAMERA permission missing: adb shell pm grant $packageName android.permission.CAMERA")
            report.line("DONE")
            return
        }

        val only = intent.getStringExtra("only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        Thread {
            val p = ProbeContext(this, report)
            writeRunInfo(dir, p, only)
            for (probe in Probe.all) {
                if (only != null && probe.name !in only) continue
                report.guard("probe ${probe.name}") { probe.run(p) }
            }
            p.close()
            report.line("DONE")
        }.start()
    }

    /** Machine-readable run identity: the host script names the result folder from it. */
    private fun writeRunInfo(dir: File, p: ProbeContext, only: Set<String>?) {
        val info = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("buildId", Build.ID)
            .put("incremental", Build.VERSION.INCREMENTAL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("probes", only?.joinToString(",") ?: Probe.all.joinToString(",") { it.name })
            .put("halLogTags", runCatching { p.activeProfiles.flatMap { it.logTags }.distinct().joinToString(",") }.getOrDefault(""))
        if (Build.VERSION.SDK_INT >= 31) info.put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        File(dir, "run.json").writeText(info.toString(2))
    }
}

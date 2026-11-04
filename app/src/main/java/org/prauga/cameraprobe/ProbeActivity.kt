/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.prauga.cameraprobe.probes.Probe
import java.io.File

/**
 * Pick the probes and run them; the run itself lives in [ProbeRun].
 * From a host: `am start -n org.prauga.cameraprobe/.ProbeActivity --ez autorun true [--es only env,vendor,zoom]`
 */
class ProbeActivity : Activity(), ProbeRun.Listener {
    private lateinit var probeScroll: ScrollView
    private lateinit var logScroll: ScrollView
    private lateinit var log: TextView
    private lateinit var run: Button
    private lateinit var probes: Button
    private lateinit var export: Button
    private lateinit var share: Button
    private val boxes = mutableMapOf<String, CheckBox>()
    private var pending: List<Probe> = emptyList()

    private val prefs by lazy { getSharedPreferences("probe", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_probe)
        probeScroll = findViewById(R.id.probeScroll)
        logScroll = findViewById(R.id.logScroll)
        log = findViewById(R.id.log)
        run = findViewById(R.id.run)
        probes = findViewById(R.id.probes)
        export = findViewById(R.id.export)
        share = findViewById(R.id.share)

        findViewById<TextView>(R.id.device).text = buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT >= 31) append("\n${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }

        val list = findViewById<LinearLayout>(R.id.probeList)
        val selected = prefs.getStringSet(KEY_SELECTED, null)
        for (probe in Probe.all) {
            val box = CheckBox(this).apply {
                text = buildString {
                    append(probe.name).append('\n').append(probe.summary)
                    if (probe.opensCamera) append(" (").append(getString(R.string.opens_camera)).append(')')
                }
                isChecked = selected?.contains(probe.name) ?: true
                setOnCheckedChangeListener { _, _ -> saveSelection() }
            }
            boxes[probe.name] = box
            list.addView(box)
        }

        run.setOnClickListener { requestRun(Probe.all.filter { boxes[it.name]?.isChecked == true }) }
        probes.setOnClickListener { showLog(false) }
        export.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, ResultsArchive.baseName(ProbeRun.resultsDir(this)) + ".zip")
            startActivityForResult(intent, REQUEST_EXPORT)
        }
        share.setOnClickListener { shareResults() }

        ProbeRun.listener = this
        if (ProbeRun.running || ProbeRun.hasResults(this)) {
            log.text = ProbeRun.lines(this).joinToString("\n", postfix = "\n")
            showLog(true)
        }
        updateButtons()

        if (savedInstanceState == null) autorun(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autorun(intent)
    }

    private fun autorun(intent: Intent) {
        val only = intent.getStringExtra("only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        if (only != null || intent.getBooleanExtra("autorun", false)) {
            requestRun(Probe.all.filter { only == null || it.name in only })
        }
    }

    override fun onDestroy() {
        if (ProbeRun.listener === this) ProbeRun.listener = null
        super.onDestroy()
    }

    private fun saveSelection() {
        prefs.edit().putStringSet(KEY_SELECTED, boxes.filterValues { it.isChecked }.keys).apply()
    }

    private fun requestRun(chosen: List<Probe>) {
        if (ProbeRun.running) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pending = chosen
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (chosen.isEmpty()) {
            Toast.makeText(this, R.string.no_probe_selected, Toast.LENGTH_SHORT).show()
            return
        }
        log.text = ""
        showLog(true)
        ProbeRun.start(this, chosen)
        updateButtons()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestRun(pending)
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val dir = ProbeRun.resultsDir(this)
        Thread {
            val error = runCatching {
                contentResolver.openOutputStream(uri)!!.use { ResultsArchive.write(dir, it) }
            }.exceptionOrNull()
            runOnUiThread {
                val message = error?.let { getString(R.string.export_failed, it.message) } ?: getString(R.string.exported)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun shareResults() {
        val dir = ProbeRun.resultsDir(this)
        val shareDir = ResultsProvider.shareDir(this)
        Thread {
            val result = runCatching {
                shareDir.deleteRecursively()
                shareDir.mkdirs()
                val name = ResultsArchive.baseName(dir)
                val zip = File(shareDir, "$name.zip")
                zip.outputStream().use { ResultsArchive.write(dir, it) }
                name to ResultsProvider.uri(zip)
            }
            runOnUiThread {
                result.onSuccess { (name, uri) ->
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject, name))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri(name, uri)
                    startActivity(Intent.createChooser(intent, getString(R.string.share)))
                }.onFailure {
                    Toast.makeText(this, getString(R.string.export_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onLine(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onFinished() = updateButtons()

    private fun showLog(show: Boolean) {
        logScroll.visibility = if (show) View.VISIBLE else View.GONE
        probeScroll.visibility = if (show) View.GONE else View.VISIBLE
        updateButtons()
    }

    private fun updateButtons() {
        val running = ProbeRun.running
        run.isEnabled = !running
        run.setText(if (running) R.string.running else R.string.run)
        probes.isEnabled = !running && logScroll.visibility == View.VISIBLE
        export.isEnabled = ProbeRun.hasResults(this)
        share.isEnabled = export.isEnabled
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val REQUEST_EXPORT = 2
        private const val KEY_SELECTED = "selected"
    }
}

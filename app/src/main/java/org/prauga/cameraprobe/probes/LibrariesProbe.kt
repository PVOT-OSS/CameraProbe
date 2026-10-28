/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe.probes

import android.content.pm.SharedLibraryInfo
import dalvik.system.DexFile
import org.prauga.cameraprobe.ProbeContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * What the ROM ships and what this untrusted_app process may actually use:
 * declared shared libraries (discovery), linked libraries (manifest), public native libraries,
 * and the accelerator/codec device nodes those libraries need.
 */
object LibrariesProbe : Probe {
    override val name = "libraries"

    private val INTERESTING = Regex("camera|cam|isp|image|imaging|photo|vision|jpeg|jpg|hdr|night|bokeh|depth|ai|ml|nn|npu|tpu|dsp|gpu|opencl|vulkan|neuron|apu|hexagon|snpe|qnn|edgetpu|gxp|aion|eden|enn", RegexOption.IGNORE_CASE)

    private val PUBLIC_LIB_DIRS = listOf("/system/etc", "/system_ext/etc", "/product/etc", "/vendor/etc", "/odm/etc")

    /** Accelerator/codec nodes across SoC families; EACCES = exists but not for us. */
    private val DEVICE_NODES = listOf(
        "/dev/gxp", "/dev/edgetpu", "/dev/edgetpu-soc", "/dev/video12",
        "/dev/adsprpc-smd", "/dev/cdsprpc-smd", "/dev/sdsprpc-smd", "/dev/ion", "/dev/dma_heap/system",
        "/dev/kgsl-3d0", "/dev/mali0", "/dev/apusys", "/dev/vertex10", "/dev/npu",
    )

    override fun run(p: ProbeContext) {
        val r = p.r
        r.section(name)
        val pm = p.context.packageManager

        r.guard("declared shared libraries") {
            val all: List<SharedLibraryInfo> = pm.getSharedLibraries(0)
            val lines = all.map { lib ->
                val type = when (lib.type) {
                    SharedLibraryInfo.TYPE_BUILTIN -> "builtin"
                    SharedLibraryInfo.TYPE_DYNAMIC -> "dynamic"
                    SharedLibraryInfo.TYPE_STATIC -> "static"
                    else -> "type${lib.type}"
                }
                "${lib.name} [$type] v=${lib.longVersion} by=${lib.declaringPackage.packageName}"
            }.sorted()
            val interesting = lines.filter { INTERESTING.containsMatchIn(it.substringBefore(' ')) }
            r.line("declared shared libraries: ${all.size}, camera/imaging/ML related: ${interesting.size}")
            interesting.forEach { r.line("  $it") }
            r.file("shared-libraries.txt", lines.joinToString("\n"))
        }

        r.guard("public native libraries") {
            val sb = StringBuilder()
            for (dir in PUBLIC_LIB_DIRS) {
                val files = File(dir).listFiles { f -> f.name.startsWith("public.libraries") }.orEmpty()
                for (f in files) {
                    val libs = runCatching { f.readLines() }.getOrElse { listOf("<${it.message}>") }
                        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    r.line("  ${f.path}: ${libs.size} libs; related: ${libs.filter { INTERESTING.containsMatchIn(it) }}")
                    sb.append("## ${f.path}\n").append(libs.joinToString("\n")).append('\n')
                }
            }
            r.file("public-native-libraries.txt", sb.toString())
        }

        val linked = p.context.applicationInfo.sharedLibraryFiles.orEmpty()
        r.line("linked into this app (${linked.size}): ${linked.joinToString()}")
        for (jar in linked.filter { it.endsWith(".jar") || it.endsWith(".apk") }) {
            r.guard("dex $jar") {
                @Suppress("DEPRECATION")
                val classes = DexFile(jar).entries().toList()
                r.line("  $jar: ${classes.size} classes ${classes.take(5)}")
                r.file("classes-${jar.substringAfterLast('/')}.txt", classes.joinToString("\n"))
            }
        }

        val declared = runCatching { declaredNativeLibraries(p) }.getOrElse {
            r.line("  !! reading our manifest failed: ${it.message}")
            emptyList()
        }
        for (lib in declared) {
            val stem = lib.removePrefix("lib").removeSuffix(".so")
            try {
                System.loadLibrary(stem)
                r.line("loadLibrary $lib: OK")
            } catch (t: Throwable) {
                r.line("loadLibrary $lib: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            }
        }

        for (node in DEVICE_NODES) {
            val f = File(node)
            val result = try {
                FileInputStream(f).close()
                "OPEN OK"
            } catch (t: Throwable) {
                t.message?.substringAfter(": ") ?: t.javaClass.simpleName
            }
            if (!result.contains("ENOENT")) r.line("open $node: $result")
        }
    }

    /** The <uses-native-library> names from our own manifest, read back from the installed APK. */
    private fun declaredNativeLibraries(p: ProbeContext): List<String> {
        val xml = p.context.assets.openXmlResourceParser("AndroidManifest.xml")
        val names = mutableListOf<String>()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "uses-native-library") {
                xml.getAttributeValue(ANDROID_NS, "name")?.let(names::add)
            }
        }
        return names
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}

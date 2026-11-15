/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionMajor = 1
val versionMinor = 1
val versionPatch = 0

android {
    namespace = "org.prauga.cameraprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.prauga.cameraprobe"
        // Android 9: SessionConfiguration, physical cameras and session keys all exist from here.
        minSdk = 28
        targetSdk = 36
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = "$versionMajor.$versionMinor.$versionPatch"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

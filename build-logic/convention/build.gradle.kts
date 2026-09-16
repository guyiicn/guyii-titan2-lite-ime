// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `java-gradle-plugin`
}

group = "net.guyii.ime.build_logic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.kotlinx.serialization.json)
}

gradlePlugin {
    plugins {
        register("androidAppConvention") {
            id = "net.guyii.ime.app-convention"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("dataChecksums") {
            id = "net.guyii.ime.data-checksums"
            implementationClass = "DataChecksumsPlugin"
        }
        register("nativeAppConvention") {
            id = "net.guyii.ime.native-app-convention"
            implementationClass = "NativeAppConventionPlugin"
        }
        register("nativeCacheHash") {
            id = "net.guyii.ime.native-cache-hash"
            implementationClass = "NativeCacheHashPlugin"
        }
        register("openccData") {
            id = "net.guyii.ime.opencc-data"
            implementationClass = "OpenCCDataPlugin"
        }
    }
}

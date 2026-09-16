/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("unused")
class NativeAppConventionPlugin : NativeBaseConventionPlugin() {
    private val Project.librimeVersion: String
        get() =
            runCmd(
                "git -C app/src/main/jni/librime describe " +
                    "--tags --long --always --exclude=latest",
                // The native sources are vendored rather than checked out as submodules,
                // so there is no git metadata to describe. Falls back to the version the
                // source itself declares (librime's CMakeLists `rime_version`).
                default = "1.17.0",
            )

    private val Project.openccVersion: String
        get() =
            runCmd(
                "git -C app/src/main/jni/OpenCC describe --tags --long --always",
                // Same as above; OpenCC's CMakeLists declares 1.2.
                default = "1.2.0",
            )

    override fun apply(target: Project) {
        super.apply(target)

        target.pluginManager.apply("com.android.application")

        target.extensions.configure<ApplicationExtension> {
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
            defaultConfig {
                buildConfigField("String", "LIBRIME_VERSION", "\"${target.librimeVersion}\"")
                buildConfigField("String", "OPENCC_VERSION", "\"${target.openccVersion}\"")
            }
        }
    }
}

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    // AGP 9.3.1 ships with built-in Kotlin support for Android sources,
    // matching the upstream sdl-kmp example layout
    // (examples/sdl_renderer/android/build.gradle.kts). We do NOT apply
    // `org.jetbrains.kotlin.android` separately because that plugin id
    // is already on the buildscript classpath via the root project's
    // KMP plugin and a second version constraint would be rejected.
    alias(libs.plugins.android.application)
}

val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

/**
 * Copies the per-ABI `libmain.so` produced by the `:app` subproject's
 * `androidNative*` link tasks into AGP's jniLibs directory so the APK
 * ends up with the right native shared library in each ABI split.
 *
 * The vendored SDL3 Android Java glue (`org.libsdl.app.SDLActivity` and
 * friends) lives in [src/main/java]. The C library is statically
 * linked from the sdl-kmp klib, so we don't ship the SDL3 .so files
 * at all — the vendored Java code drives the existing one.
 */
abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @TaskAction
    fun run() {
        // The `:app` subproject produces `app/build/bin/<target>/mainDebugShared/libmain.so`.
        // We're a sibling, not a child, so we have to use the rootProject
        // directory explicitly; `project.layout.projectDirectory` here
        // would point to the `:android` directory and resolve to the
        // wrong `build/` (the project's own build dir, not :app's).
        val bin = project.rootDir.toPath().resolve("app/build/bin").toFile()
        outputDir.get().asFile.deleteRecursively()
        abis.get().forEach { (target, abi) ->
            val src = File(bin, "$target/mainDebugShared/libmain.so")
            val dstDir = File(outputDir.get().asFile, abi)
            dstDir.mkdirs()
            src.copyTo(File(dstDir, "libmain.so"), overwrite = true)
        }
    }
}

val prepareJniLibs = tasks.register<PrepareJniLibsTask>("prepareJniLibs") {
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    abis.set(androidAbis)
}

prepareJniLibs.configure {
    androidAbis.keys.forEach { target ->
        val capitalized = target.replaceFirstChar { it.uppercase() }
        dependsOn(project(":app").tasks.named("linkMainDebugShared$capitalized"))
    }
}

android {
    namespace = "cn.enaium.sysinfomonitor"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.enaium.sysinfomonitor"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Restrict the APK to the ABIs we build for in the root
            // module. The sdl-kmp klib already brings the right NDK
            // stub libraries; we only contribute libmain.so.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
        targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

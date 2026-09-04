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
 * The SDL3 Android Java glue (`org.libsdl.app.SDLActivity` and friends)
 * comes from the sdl-kmp 1.0.10 `sdl-kmp-android-jvm` AAR. The C
 * library is statically linked from the sdl-kmp klib, so we don't ship
 * the AAR's `libsdl_jni.so` at all — we exclude it below and let the
 * bundled Java code drive our own statically-linked `libmain.so`.
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

dependencies {
    // sdl-kmp's android variant resolves to the sdl-kmp-android AAR, which
    // transitively brings in sdl-kmp-android-jvm with SDL3's Java layer
    // (SDLActivity and friends). We statically link SDL3 into libmain.so
    // via the sdl-kmp klib, so the AAR's own libsdl_jni.so is excluded
    // from the APK (see packaging below).
    implementation(libs.sdl.kmp)
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
    packaging {
        jniLibs {
            // The sdl-kmp-android-jvm AAR ships a per-ABI libsdl_jni.so
            // (SDL3 + JNI bridge) next to its Java glue. SDL3 is already
            // statically linked into libmain.so, so shipping it would
            // duplicate the library and bloat every ABI split by ~3 MB.
            // jniLibs exclude patterns are relative to the ABI dir, hence
            // the `**/` prefix.
            excludes += "**/libsdl_jni.so"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

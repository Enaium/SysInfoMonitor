import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

// Kotlin/Native's own Android toolchain sysroot (api 26) ships the NDK
// stub libraries (libEGL, libGLESv2, libOpenSLES, libaaudio, ...) that
// SDL3's android drivers reference at link time. Point -L at the
// per-ABI directory so the libmain.so link resolves them without
// needing an extra NDK install.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: providers.gradleProperty("konan.data.dir").getOrElse("${System.getProperty("user.home")}/.konan")
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    // The per-ABI NDK stub libraries (libEGL, libGLESv2, libaaudio, ...)
    // live under an API-level subdirectory (e.g. `26`) that differs
    // across toolchain versions/layouts, so scan for the directory that
    // actually contains libaaudio.so instead of assuming a fixed path.
    val abiLib = File(toolchain, "sysroot/usr/lib/$triple")
    val candidate = abiLib.listFiles()
        ?.filter { it.isDirectory && File(it, "libaaudio.so").exists() }
        ?.maxByOrNull { it.name.toIntOrNull() ?: 0 }
    return candidate?.absolutePath
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.sysinfomonitor.SystemMonitor"
        }
    }

    macosArm64 {
        binaries.executable()
    }

    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    linuxArm64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable {
            // sdl-kmp's hidapi backend references HidD_*/HidP_* symbols on
            // Windows; the mingw toolchain ships these in libhid/libsetupapi
            // but sdl-kmp doesn't declare them, so link them explicitly.
            linkerOpts("-lhid", "-lsetupapi")
        }
    }

    // Android native targets build libmain.so with an exported SDL_main
    // entry point. The SDL3 static library is linked in from the
    // sdl-kmp klib; the Kotlin/Native android sysroot provides the NDK
    // system libraries it references. The compiler-rt builtins
    // embedded in the sdl-kmp klib overlap with K/N's bundled libgcc
    // on some ABIs (e.g. __sync_* on armv7); allow duplicates so the
    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
            // The imgui-kmp cinterop klib declares dependencies that aren't
            // being auto-linked by the K/N 2.4.0 Gradle plugin in this
            // configuration, so we link the prebuilt cross-compiled
            // static library directly. The path is determined by the
            // imgui-kmp project being built in the same Gradle session
            // (see `kotlinTransformedCInteropMetadataLibraries`).
            run {
                val imguiA = file("/home/enaium/Projects/imgui-kmp/imgui-kmp/build/native/androidNativeArm64/libimgui.a")
                if (imguiA.exists()) linkerOpts(imguiA.absolutePath)
            }
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    sourceSets {
        // Match sdl-kmp's `examples/sdl_renderer` layout: explicitly
        // create the `nativeMain` and `androidMain` source sets, then
        // attach the per-target source sets. The default hierarchy
        // template (Kotlin 2.4+) is disabled via `gradle.properties`
        // because it would make KGP attempt to decorate
        // `KotlinAndroidTarget` with a class from AGP 7.x, which
        // fails on AGP 8/9.
        val nativeMain = create("nativeMain") {
            dependsOn(getByName("commonMain"))
        }
        val androidMain = create("androidMain") {
            dependsOn(getByName("commonMain"))
        }
        listOf(
            "macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64",
        ).forEach { name ->
            getByName("${name}Main").dependsOn(nativeMain)
        }
        listOf(
            "androidNativeArm64", "androidNativeArm32",
            "androidNativeX64", "androidNativeX86",
        ).forEach { name ->
            getByName("${name}Main").dependsOn(androidMain)
        }

        commonMain {
            dependencies {
                // sysinfo-kmp supplies the Rust-backed system info
                // snapshot. sdl-kmp + imgui-kmp give us the windowing,
                // the imgui bindings, and the SDL renderer/gpu/platform
                // backends.
                implementation(libs.sysinfo.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
            }
        }
        // The JVM target needs the matching host JNI .so/.dylib on the
        // classpath: imgui-kmp, sysinfo-kmp and sdl-kmp each have a
        // NativeLoader that extracts the artifact from the classpath at
        // runtime. The linux-x86_64 variants are the right pair for a
        // Linux x64 host.
        jvmMain {
            dependencies {
                runtimeOnly(libs.sysinfo.kmp.jni.jvm)
                runtimeOnly(libs.sdl.kmp.jni.jvm)
                runtimeOnly(libs.imgui.kmp.jni.jvm)
            }
        }
    }
}

// macOS JVM needs the main thread to be the AppKit thread.
tasks.withType(JavaExec::class.java).configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

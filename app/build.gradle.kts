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
    // The per-ABI NDK stub libraries (libEGL, libGLESv2, libaaudio, ...)
    // live under an API-level subdirectory that differs across toolchain
    // versions/layouts, so locate libaaudio.so for this triple instead of
    // assuming a fixed path. Two layouts occur in the wild:
    //   - toolchain sysroot:  <root>/sysroot/usr/lib/<triple>/<api>/
    //   - NDK sysroot:        <root>/android-<api>/arch-<arch>/usr/lib/
    // Search both, recursing for libaaudio.so.
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    // The konan NDK toolchain/sysroot layout varies across hosts and
    // versions (sysroot/usr/lib/<triple>/<api>/, android-<api>/arch-<arch>/,
    // etc.), so fall back to a full recursive search of the konan
    // dependencies tree for libaaudio.so whose path marks this ABI.
    val arch = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> return null
    }
    val depsDir = File(konanData, "dependencies")
    if (!depsDir.isDirectory) return null
    // Boundary-precise markers: the exact triple (i686-linux-android vs
    // x86_64-linux-android, arm-linux-androideabi vs aarch64-...) and the
    // NDK arch dir with a trailing slash (/arch-x86/ so it cannot match
    // arch-x86_64). Substring markers would pick the wrong ABI's lib.
    val markers = listOf(triple, "/arch-$arch/", "/$arch/usr/")
    val hit = depsDir.walkTopDown()
        .firstOrNull { f ->
            f.name == "libaaudio.so" && markers.any { f.path.contains(it) }
        }
    return hit?.parentFile?.absolutePath
}

// Locate the LLVM clang bundled with the Kotlin/Native toolchain (used to
// cross-compile src/linuxArm64Main/c/atomic_helpers.c to an aarch64 object).
// The toolchain is only downloaded by the first native compile/link task, so
// this must be called at task execution time, not configuration time.
fun konanClang(): File? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val depsDir = File(konanData, "dependencies")
    if (!depsDir.isDirectory) return null
    return depsDir.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("llvm-") }
        ?.map { File(it, "bin/clang") }
        ?.filter { it.exists() }
        ?.maxByOrNull { it.name }
}

// The sdl-kmp linuxArm64 SDL3 static library is built with GCC and
// references the aarch64 libgcc outline-atomic helpers (__aarch64_cas4_sync,
// __aarch64_ldadd4_sync, ...) that ship only in GCC 9+'s libgcc; the GCC 8.3
// sysroot bundled with Kotlin/Native predates them. We compile our own
// freestanding implementations (src/linuxArm64Main/c/atomic_helpers.c) with
// the K/N-bundled clang and link the object into the linuxArm64 executable.
// The object path is fixed at configuration time, so the link task simply
// depends on the compile task; clang itself is resolved at execution time
// (the toolchain is downloaded by an earlier compile/link task in CI).
val linuxArm64AtomicHelpersObj = layout.buildDirectory.file("generated/linuxArm64AtomicHelpers/atomic_helpers.o")
val linuxArm64AtomicHelpersSrc = file("src/linuxArm64Main/c/atomic_helpers.c")
val compileLinuxArm64AtomicHelpers = tasks.register("compileLinuxArm64AtomicHelpers") {
    inputs.file(linuxArm64AtomicHelpersSrc)
    outputs.file(linuxArm64AtomicHelpersObj)
    // clang appears only after the K/N compiler has bootstrapped its
    // toolchain; piggyback on the compile task that must already have run.
    dependsOn("compileKotlinLinuxArm64")
    doLast {
        val clang = konanClang() ?: error(
            "Cannot locate the Kotlin/Native LLVM clang under ~/.konan/dependencies. " +
            "Run a native compile first so the toolchain is downloaded."
        )
        val out = providers.exec {
            commandLine(
                clang.absolutePath,
                "--target=aarch64-unknown-linux-gnu",
                "-march=armv8-a",
                "-mno-outline-atomics",
                "-ffreestanding",
                "-O2",
                "-c",
                linuxArm64AtomicHelpersSrc.absolutePath,
                "-o",
                linuxArm64AtomicHelpersObj.get().asFile.absolutePath,
            )
        }.result.get()
        if (out.exitValue != 0) {
            throw GradleException("clang failed (exit ${out.exitValue})")
        }
    }
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
        binaries.executable {
            // sdl-kmp's SDL3 static library for linuxArm64 was built with
            // GCC and references aarch64 libgcc outline-atomic helpers that
            // K/N's GCC 8.3 sysroot does not provide; supply our own
            // implementations (see compileLinuxArm64AtomicHelpers above).
            linkerOpts(linuxArm64AtomicHelpersObj.get().asFile.absolutePath)
            linkTaskProvider.configure { dependsOn(compileLinuxArm64AtomicHelpers) }
        }
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
                //
                // The JVM natives come in transitively: each -kmp
                // library's -jvm artifact depends on every platform's
                // jni-jvm-* sibling, and its NativeLoader extracts the one
                // matching the host OS/arch at runtime. No explicit
                // runtimeOnly dependency needed here.
                implementation(libs.sysinfo.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
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

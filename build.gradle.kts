import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "cn.enaium"
version = "1.0-SNAPSHOT"

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

    mingwX64 {
        binaries.executable()
    }

    // The libraries sysinfo-kmp, sdl-kmp and imgui-kmp publish klibs for the
    // Android native targets, so we keep them declared. iOS/tvOS are omitted
    // because none of the three libraries ship a klib for them.
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                // sysinfo-kmp supplies the Rust-backed system info snapshot.
                // sdl-kmp + imgui-kmp give us the windowing, the imgui
                // bindings, and the SDL renderer/gpu/platform backends.
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

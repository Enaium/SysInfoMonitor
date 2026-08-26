// Root project: declare the plugins here with `apply false` so their
// service classes are loaded exactly once for the whole build. Each
// subproject then re-declares the same plugin to apply it locally.
// This matches sdl-kmp's own `examples/sdl_renderer` and
// `examples/sdl_renderer:android` setup, which keeps the KGP and AGP
// build services in separate class loaders even though they share a
// daemon.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
}

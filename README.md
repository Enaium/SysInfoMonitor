# SysInfoMonitor

A cross-platform system monitor written in Kotlin Multiplatform, with a
real-time desktop UI rendered through Dear ImGui + ImPlot on top of SDL3.

![](https://img.cdn1.vip/i/6a8d224bd5941_1787634251.webp)

The application pulls system data from the [sysinfo] Rust crate (via
[sysinfo-kmp]), draws a custom dashboard with [imgui-kmp], and uses
[sdl-kmp] for windowing, input, and 2D rendering.

| | |
| --- | --- |
| Targets | JVM (Linux / macOS / Windows desktop), Kotlin/Native (Linux x64, macOS arm64/x64, mingw x64, Android NDK), `androidNativeArm64/Arm32/X64/X86` |
| UI | 9 tabs: Overview, CPU, Memory, Processes, Disks, Network, Sensors, Users, System |
| Plots | Time-series charts for CPU / memory / swap, per-core usage bars, network + disk throughput, sensor temperatures |
| Backend | SDL 2D renderer with vsync; auto-falls back to SDL's `dummy` video driver when no display is available (headless CI, SSH) |

## Features

- **Live system snapshot** at a configurable data rate, built on top of the
  Rust `sysinfo` crate (per-process CPU%, memory, swap, disk I/O,
  network counters, temperature sensors, users, groups, motherboard
  + product metadata).
- **Process table** with PID / name / CPU% / memory / status / user /
  thread count / runtime; sort + filter, color-coded process status
  badges, hard cap of 50 visible rows (the underlying snapshot may
  contain thousands of processes; the cap keeps per-frame cost bounded).
- **Network interfaces** with state, MAC, MTU, RX/TX bytes + packet
  counts, error counters, IP listing.
- **Disks** with mount point, filesystem, kind (HDD / SSD / unknown),
  used / total, read / write byte counters, used-space progress bar.
- **Sensors** with current / max / critical thresholds and a bar chart.
- **Host info**: hostname, OS / kernel / distribution versions, CPU
  architecture, physical / logical core count, uptime, load average,
  cgroup limits, open-file limit.
- **Fullscreen ImGui host window** that covers the entire SDL viewport
  with no title bar / resize / move decoration; the SDL window itself
  stays a regular desktop window so the OS taskbar and window list
  still work. Pass `--windowed` to opt out.
- **Headless mode**: with no display, the app sets `SDL_VIDEO_DRIVER=dummy`
  and renders frames anyway — useful for CI smoke tests.

## Performance

The dashboard is built to stay responsive on systems with thousands of
live processes (where the cheap UI cost is dwarfed by the cost of
walking `/proc` once per data refresh). The main loop is structured as:

1. **Drain all queued SDL events** every tick (no batching) so window
   dragging and mouse moves are processed promptly. While a window is
   being dragged, the loop spins instead of sleeping, so the OS keeps
   getting its events drained at the display refresh rate.
2. **Amortized data refresh**: the expensive `system.refreshAll` (which
   walks the entire process table and reads per-process disk I/O
   counters) runs only every Nth data refresh. The cheap path
   (`refreshCpu` + `refreshMemory` only) runs at the full data rate and
   reuses the previous snapshot's process / disk / network lists.
3. **Data refresh is skipped during a window drag** so the 80-2000 ms
   sysinfo call cannot stall a drag frame.
4. **UI rebuild on demand**: the ImGui draw data is rebuilt only when
   the data changed, the periodic UI tick elapsed, or the user is
   interacting — capped at `uiHz` (15 Hz default) on idle, and at the
   display refresh rate while dragging.
5. **No work on idle ticks**: the renderer doesn't `clear`, doesn't
   `renderDrawData`, and doesn't `present` when nothing has changed.
6. **Allocation-free formatters**: `formatBytes`, `formatDuration`, etc.
   reuse a per-thread StringBuilder pool; no per-call String allocation
   in the hot path.

Measured on a Linux x86_64 host with ~3200 live processes:

| Refresh kind | Cost |
| --- | --- |
| `system.refreshAll` + counter refreshes | ~1400 ms (every 8 s by default) |
| `refreshCpu` + `refreshMemory` only | ~80 ms (every 0.5 s by default) |

## Building

The project is a standard Gradle Kotlin Multiplatform project. The
artifacts are pulled from Maven Central; no local setup is needed
beyond a JDK and the Gradle wrapper.

```bash
# Compile everything
./gradlew assemble

# Run the JVM build (Linux / macOS / Windows)
./gradlew :jvmRun

# Build a self-contained native binary
./gradlew :linkDebugExecutableLinuxX64
./build/bin/linuxX64/debugExecutable/SysInfoMonitor.kexe
```

The first run downloads the sysinfo-kmp / sdl-kmp / imgui-kmp artifacts,
which include the SDL3 shared library and the Rust sysinfo JNI bridge
(JVM) or the embedded static libraries (native). Subsequent runs reuse
the Gradle cache.

## Running

### JVM (desktop)

```bash
# Run with a window (defaults: 1280x800, fullscreen ImGui host)
./gradlew :jvmRun

# Common flags
./gradlew :jvmRun --args="--frames 30"        # exit after 30 rendered frames
./gradlew :jvmRun --args="--windowed"          # disable fullscreen ImGui host
./gradlew :jvmRun --args="--width 1600 --height 1000"
./gradlew :jvmRun --args="--refresh 1"         # data refresh rate (1-30 Hz)
./gradlew :jvmRun --args="--ui 30"             # UI rebuild cap (1-120 Hz)
```

On macOS the JVM needs `-XstartOnFirstThread` for SDL to find the AppKit
main thread; the Gradle script already adds this when the host is macOS.

### Native (Linux)

```bash
./gradlew :linkDebugExecutableLinuxX64
SDL_VIDEO_DRIVER=dummy IMGUI_KMP_FRAMES=30 \
    ./build/bin/linuxX64/debugExecutable/SysInfoMonitor.kexe
```

The native binary is fully self-contained: SDL3, the Rust sysinfo
bridge, and Dear ImGui + ImPlot are all statically linked. No system
SDL installation is required.

### Headless / CI

The dummy video driver is used automatically when no real display is
available:

```bash
SDL_VIDEO_DRIVER=dummy ./gradlew :jvmRun --args="--frames 5"
```

The first three flags set the process counts and rendering budget; the
test exits cleanly when the frame cap is reached.

## CLI flags

| Flag | Default | Range | Effect |
| --- | --- | --- | --- |
| `--frames N` | `MAX_INT` | | Exit after N rendered frames (useful for smoke tests) |
| `--width W` | 1280 | | Window width (when not fullscreen) |
| `--height H` | 800 | | Window height (when not fullscreen) |
| `--windowed` | off | | Disable the fullscreen ImGui host window |
| `--refresh N` | 2 | 1..30 | Data refresh rate in Hz |
| `--ui N` | 15 | 1..120 | UI rebuild cap in Hz |
| `--help` / `-h` | | | Print usage |

On the native target, the same parameters are read from environment
variables (`IMGUI_KMP_FRAMES`, `SYSINFO_WIDTH`, `SYSINFO_HEIGHT`,
`SYSINFO_WINDOWED`, `SYSINFO_REFRESH`, `SYSINFO_UI`).

## Architecture

```
src/commonMain/kotlin/cn/enaium/sysinfomonitor/
├── Main.kt              # main loop, event pump, schedule, vsync
├── SnapshotManager.kt   # sysinfo-kmp handles, amortized refresh
├── SystemSnapshot.kt    # mutable data class reused across refreshes
├── MetricsHistory.kt    # 240-sample ring buffer for the live plots
├── Format.kt            # allocation-free formatters (bytes, time, %)
└── ui/
    └── SystemMonitorUi.kt   # 9 tabs, tables, ImPlot charts

src/jvmMain/kotlin/.../    # @file:JvmName("SystemMonitor") + parseArgs
src/nativeMain/kotlin/.../ # top-level fun main() in default package
```

### Refresh amortization

`SnapshotManager.refresh()` is the only call that touches the Rust
sysinfo bridge. Internally it branches on `tick % processRefreshEvery`:

- **Cheap path** (every call): `system.refreshCpu` + `system.refreshMemory`
  + the previous snapshot's process/disk/network/etc. lists are reused.
- **Expensive path** (every `processRefreshEvery` calls):
  `system.refreshAll` + disks/networks/components/users/groups
  refreshes + the process list re-read.

The default `processRefreshEvery=16` × `refreshHz=2` = one full refresh
every 8 seconds. On a system with ~3000 processes that's the difference
between the UI being usable and being frozen.

### Fullscreen ImGui host

The ImGui host window (`"SysInfoMonitor##host"`) is positioned at
`(0, 0)` with size = `displaySize`, with `ImGuiWindowFlags.NO_TITLE_BAR |
NO_RESIZE | NO_MOVE | NO_BRING_TO_FRONT_ON_FOCUS | NO_NAV_FOCUS |
NO_SAVED_SETTINGS | NO_SCROLLBAR`. The SDL window itself is a regular
1280×800 desktop window (`RESIZABLE`) so the OS draws a frame and a
taskbar entry. Set `--windowed` to use a normal ImGui window with a
title bar.

## Dependencies

| | Maven coordinate | Version |
| --- | --- | --- |
| [sysinfo-kmp] | `cn.enaium:sysinfo-kmp` | 1.0.1 |
| [sdl-kmp]     | `cn.enaium.sdl:sdl-kmp`     | 1.0.9 |
| [imgui-kmp]   | `cn.enaium.imgui:imgui-kmp` | 1.0.1 |

The JVM target also pulls in per-OS JNI artifacts (`sysinfo-kmp-jni-jvm-linux-x86_64`,
`sdl-kmp-jni-jvm-linux-x86_64`, `imgui-kmp-jni-jvm-linux-x86_64`) so the
matching native shared libraries are on the classpath at runtime.

## License

MIT — Copyright (c) 2026 Enaium. See [`LICENSE`](LICENSE) for the full text.

The bound libraries ([sysinfo-kmp], [sdl-kmp], [imgui-kmp], Dear ImGui, ImPlot)
are MIT-licensed.

[sysinfo-kmp]: https://github.com/Enaium/sysinfo-kmp
[sdl-kmp]:     https://github.com/Enaium/sdl-kmp
[imgui-kmp]:   https://github.com/Enaium/imgui-kmp
[sysinfo]:     https://github.com/GuillaumeGomez/sysinfo

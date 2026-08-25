package cn.enaium.sysinfomonitor.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCol
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiStyleVar


import cn.enaium.imgui.ImGuiTableFlags
import cn.enaium.imgui.ImGuiTableColumnFlags




import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotAxis
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotFlags
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import cn.enaium.sysinfo.CGroupLimits
import cn.enaium.sysinfo.Process
import cn.enaium.sysinfo.ProcessStatus
import cn.enaium.sysinfomonitor.MetricsHistory
import cn.enaium.sysinfomonitor.SystemSnapshot
import cn.enaium.sysinfomonitor.fmt1
import cn.enaium.sysinfomonitor.fmt2
import cn.enaium.sysinfomonitor.fmtFloat1
import cn.enaium.sysinfomonitor.fmtTemp
import cn.enaium.sysinfomonitor.formatBytes
import cn.enaium.sysinfomonitor.formatDuration
import cn.enaium.sysinfomonitor.formatUptime

/**
 * ImGui + ImPlot UI for the system monitor. Owns its own UI state (selected
 * tab, sort spec, search filter) and the time-series ring buffer.
 */
class SystemMonitorUi {

    private val history = MetricsHistory(capacity = 240)

    // ============= UI state =============
    private var section: Section = Section.OVERVIEW

    private var processFilter: String = ""
    private var processSortColumn: Int = 2 // by CPU% by default
    private var processSortAscending: Boolean = false

    private var networkFilter: String = ""
    private var diskFilter: String = ""

    private var autoRefresh = true
    private var refreshHz = 4

    private var fontTextureId: Long = 0L

    /**
     * When true (default), the host ImGui window covers the entire SDL
     * viewport with no title bar / resize grip / move chrome. The SDL
     * window itself is a regular 1280x800 desktop window so the OS
     * taskbar / window list still works.
     */
    private var fullscreen: Boolean = true

    // Lazily built scratch buffer to avoid per-frame allocation.
    private val plotScratch = FloatArray(history.capacity)

    fun setFontTextureId(id: Long) {
        fontTextureId = id
    }

    /** When true (default), the host ImGui window covers the entire SDL viewport. */
    fun setFullscreen(value: Boolean) {
        fullscreen = value
    }

    /** Push a fresh data point into the time-series ring buffer. */
    fun update(snapshot: SystemSnapshot) {
        val rx = snapshot.networks.sumOf { it.receivedBytes.toLong() }.toFloat()
        val tx = snapshot.networks.sumOf { it.transmittedBytes.toLong() }.toFloat()
        val diskRead = snapshot.disks.sumOf { it.usage.readBytes.toLong() }.toFloat()
        val diskWrite = snapshot.disks.sumOf { it.usage.writtenBytes.toLong() }.toFloat()
        val memFrac = if (snapshot.totalMemory > 0uL) {
            snapshot.usedMemory.toFloat() / snapshot.totalMemory.toFloat()
        } else 0f
        val swapFrac = if (snapshot.totalSwap > 0uL) {
            snapshot.usedSwap.toFloat() / snapshot.totalSwap.toFloat()
        } else 0f
        history.push(
            cpu = snapshot.globalCpuUsage.coerceIn(0f, 100f),
            memFrac = memFrac.coerceIn(0f, 1f) * 100f,
            swapFrac = swapFrac.coerceIn(0f, 1f) * 100f,
            rx = rx,
            tx = tx,
            diskRead = diskRead,
            diskWrite = diskWrite,
        )
    }

    fun draw(snapshot: SystemSnapshot) {
        if (fullscreen) {
            // Fill the whole ImGui viewport: position (0,0), size =
            // display size, no title bar / resize / move. The imgui display
            // size is set in Main.kt from window.sizeInPixels every frame.
            val viewport = ImGui.getIO().displaySize
            ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
            ImGui.setNextWindowSize(viewport, ImGuiCond.ALWAYS)
            val hostFlags = ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
                ImGuiWindowFlags.NO_NAV_FOCUS or
                ImGuiWindowFlags.NO_SAVED_SETTINGS or
                ImGuiWindowFlags.NO_SCROLLBAR
            ImGui.pushStyleVarFloat(ImGuiStyleVar.WINDOW_ROUNDING, 0f)
            ImGui.pushStyleVarFloat(ImGuiStyleVar.WINDOW_BORDER_SIZE, 0f)
            ImGui.pushStyleVarVec2(ImGuiStyleVar.WINDOW_PADDING, ImVec2(8f, 8f))
            if (ImGui.begin("SysInfoMonitor##host", null, hostFlags)) {
                drawContents(snapshot)
            }
            ImGui.end()
            ImGui.popStyleVar(3)
        } else {
            drawContents(snapshot)
        }
    }

    fun close() {
        // Nothing to release; imgui/ImPlot contexts are owned by the caller.
    }

    private fun drawContents(snapshot: SystemSnapshot) {
        drawSettingsBar(snapshot)
        drawTabBar()
        when (section) {
            Section.OVERVIEW -> drawOverview(snapshot)
            Section.CPU -> drawCpu(snapshot)
            Section.MEMORY -> drawMemory(snapshot)
            Section.PROCESSES -> drawProcesses(snapshot)
            Section.DISKS -> drawDisks(snapshot)
            Section.NETWORK -> drawNetwork(snapshot)
            Section.SENSORS -> drawSensors(snapshot)
            Section.USERS -> drawUsers(snapshot)
            Section.SYSTEM -> drawSystem(snapshot)
        }
    }

    // ============= Sections =============

    private fun drawSettingsBar(snapshot: SystemSnapshot) {
        val auto = booleanArrayOf(autoRefresh)
        if (ImGui.checkbox("Auto refresh", auto)) autoRefresh = auto[0]
        ImGui.sameLine()
        val hz = intArrayOf(refreshHz)
        ImGui.setNextItemWidth(120f)
        if (ImGui.sliderInt("Hz", hz, 1, 30)) refreshHz = hz[0]
        ImGui.sameLine()
        ImGui.text("Uptime: ${formatUptime(snapshot.uptimeSeconds)}")
        ImGui.sameLine()
        ImGui.text("Host: ${snapshot.hostName ?: "-"} (${snapshot.osName ?: "-"})")
    }

    private fun drawTabBar() {
        if (ImGui.beginTabBar("##sections")) {
            Section.entries.forEach { s ->
                if (ImGui.beginTabItem(s.label)) {
                    section = s
                    ImGui.endTabItem()
                }
            }
            ImGui.endTabBar()
        }
    }

    private fun drawOverview(snapshot: SystemSnapshot) {
        ImGui.columns(4, "##overview-cards", false)
        headline("CPU", "${fmt1(snapshot.globalCpuUsage.toDouble())} %")
        ImGui.nextColumn()
        headline("Memory", "${fmt1(pct(snapshot.usedMemory, snapshot.totalMemory).toDouble())} %")
        ImGui.nextColumn()
        headline("Swap", "${fmt1(pct(snapshot.usedSwap, snapshot.totalSwap).toDouble())} %")
        ImGui.nextColumn()
        headline("Processes", snapshot.processes.size.toString())
        ImGui.columns(1)
        ImGui.spacing()
        ImGui.separator()
        ImGui.spacing()

        if (ImPlot.beginPlot("CPU / Memory", ImVec2(-1f, 220f), ImPlotFlags.NONE)) {
            ImPlot.setupAxes("sample", "%", ImPlotFlags.NONE, ImPlotFlags.NONE)
            ImPlot.setupAxisLimits(ImPlotAxis.X1, 0.0, history.capacity.toDouble(), ImPlotCond.ALWAYS)
            ImPlot.setupAxisLimits(ImPlotAxis.Y1, 0.0, 100.0, ImPlotCond.ALWAYS)
            history.readOrdered(history.globalCpu, plotScratch)
            ImPlot.plotLine("CPU", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            history.readOrdered(history.memoryUsedFraction, plotScratch)
            ImPlot.plotLine("Memory", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            history.readOrdered(history.swapUsedFraction, plotScratch)
            ImPlot.plotLine("Swap", plotScratch, spec = ImPlotSpec(lineWeight = 1f))
            ImPlot.endPlot()
        }

        if (ImPlot.beginPlot("Network throughput (bytes/s)", ImVec2(-1f, 220f), ImPlotFlags.NONE)) {
            ImPlot.setupAxes("sample", "B/s", ImPlotFlags.NONE, ImPlotFlags.NONE)
            ImPlot.setupAxisLimits(ImPlotAxis.X1, 0.0, history.capacity.toDouble(), ImPlotCond.ALWAYS)
            val maxNet = (history.netRxBytesPerSec.max() + history.netTxBytesPerSec.max()).coerceAtLeast(1f)
            ImPlot.setupAxisLimits(ImPlotAxis.Y1, 0.0, maxNet.toDouble() * 1.1, ImPlotCond.ALWAYS)
            history.readOrdered(history.netRxBytesPerSec, plotScratch)
            ImPlot.plotLine("RX", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            history.readOrdered(history.netTxBytesPerSec, plotScratch)
            ImPlot.plotLine("TX", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            ImPlot.endPlot()
        }

        if (ImPlot.beginPlot("Disk throughput (bytes/s)", ImVec2(-1f, 220f), ImPlotFlags.NONE)) {
            ImPlot.setupAxes("sample", "B/s", ImPlotFlags.NONE, ImPlotFlags.NONE)
            ImPlot.setupAxisLimits(ImPlotAxis.X1, 0.0, history.capacity.toDouble(), ImPlotCond.ALWAYS)
            val maxDisk = (history.diskReadBytesPerSec.max() + history.diskWriteBytesPerSec.max()).coerceAtLeast(1f)
            ImPlot.setupAxisLimits(ImPlotAxis.Y1, 0.0, maxDisk.toDouble() * 1.1, ImPlotCond.ALWAYS)
            history.readOrdered(history.diskReadBytesPerSec, plotScratch)
            ImPlot.plotLine("Read", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            history.readOrdered(history.diskWriteBytesPerSec, plotScratch)
            ImPlot.plotLine("Write", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            ImPlot.endPlot()
        }
    }

    private fun drawCpu(snapshot: SystemSnapshot) {
        ImGui.text("Logical cores: ${snapshot.cpus.size}  (${snapshot.cpuArch})")
        snapshot.loadAverage?.let { la ->
            ImGui.text("Load avg: 1m ${fmt2(la.one)}  5m ${fmt2(la.five)}  15m ${fmt2(la.fifteen)}")
        } ?: ImGui.textDisabled("Load avg: not available")
        ImGui.text("Min CPU update interval: ${snapshot.minimumCpuUpdateIntervalMs} ms")
        ImGui.separator()

        if (ImPlot.beginPlot("Per-core CPU usage (%)", ImVec2(-1f, 260f))) {
            ImPlot.setupAxes("core", "%", ImPlotFlags.NONE, ImPlotFlags.NONE)
            ImPlot.setupAxisLimits(ImPlotAxis.Y1, 0.0, 100.0, ImPlotCond.ALWAYS)
            val xs = FloatArray(snapshot.cpus.size) { it.toFloat() }
            val ys = FloatArray(snapshot.cpus.size) { i -> snapshot.cpus.getOrNull(i)?.usage?.coerceIn(0f, 100f) ?: 0f }
            ImPlot.plotBars("CPU", xs, ys, 0.6, ImPlotSpec())
            ImPlot.endPlot()
        }

        if (ImGui.beginTable("##cpus", 5, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG or ImGuiTableFlags.SCROLL_Y)) {
            ImGui.tableSetupColumn("#", ImGuiTableColumnFlags.WIDTH_FIXED, 40f)
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("Vendor", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("Brand", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("Freq (MHz)", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableHeadersRow()
            snapshot.cpus.forEachIndexed { i, cpu ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0); ImGui.text("$i")
                ImGui.tableSetColumnIndex(1); ImGui.text(cpu.name.ifEmpty { "?" })
                ImGui.tableSetColumnIndex(2); ImGui.text(cpu.vendorId.ifEmpty { "?" })
                ImGui.tableSetColumnIndex(3); ImGui.text(cpu.brand.ifEmpty { "?" })
                ImGui.tableSetColumnIndex(4); ImGui.text(cpu.frequencyMHz.toString())
            }
            ImGui.endTable()
        }
    }

    private fun drawMemory(snapshot: SystemSnapshot) {
        bar("Memory", snapshot.usedMemory, snapshot.totalMemory)
        bar("Swap", snapshot.usedSwap, snapshot.totalSwap)
        ImGui.text("Total: ${formatBytes(snapshot.totalMemory)}")
        ImGui.text("Used:  ${formatBytes(snapshot.usedMemory)}")
        ImGui.text("Available: ${formatBytes(snapshot.availableMemory)}")
        ImGui.text("Free: ${formatBytes(snapshot.freeMemory)}")
        ImGui.separator()
        ImGui.text("Swap total: ${formatBytes(snapshot.totalSwap)}")
        ImGui.text("Swap used:  ${formatBytes(snapshot.usedSwap)}")
        ImGui.text("Swap free:  ${formatBytes(snapshot.freeSwap)}")
        snapshot.cgroupLimits?.let {
            ImGui.separator()
            ImGui.text("CGroup limits:")
            ImGui.text("  total memory: ${formatBytes(it.totalMemory)}")
            ImGui.text("  free memory:  ${formatBytes(it.freeMemory)}")
            ImGui.text("  free swap:    ${formatBytes(it.freeSwap)}")
            ImGui.text("  rss:          ${formatBytes(it.rss)}")
        }
        ImGui.separator()
        ImGui.text("Open files limit: ${snapshot.openFilesLimit?.toString() ?: "-"}")

        if (ImPlot.beginPlot("Memory & Swap (%)", ImVec2(-1f, 220f))) {
            ImPlot.setupAxes("sample", "%", ImPlotFlags.NONE, ImPlotFlags.NONE)
            ImPlot.setupAxisLimits(ImPlotAxis.X1, 0.0, history.capacity.toDouble(), ImPlotCond.ALWAYS)
            ImPlot.setupAxisLimits(ImPlotAxis.Y1, 0.0, 100.0, ImPlotCond.ALWAYS)
            history.readOrdered(history.memoryUsedFraction, plotScratch)
            ImPlot.plotLine("Memory", plotScratch, spec = ImPlotSpec(lineWeight = 1.5f))
            history.readOrdered(history.swapUsedFraction, plotScratch)
            ImPlot.plotLine("Swap", plotScratch, spec = ImPlotSpec(lineWeight = 1f))
            ImPlot.endPlot()
        }
    }

    private fun drawProcesses(snapshot: SystemSnapshot) {
        val newFilter = ImGui.inputText("Filter (name / pid / cmd)", processFilter)
        if (newFilter != null) processFilter = newFilter
        ImGui.sameLine()
        ImGui.textDisabled("(${snapshot.processes.size} processes)")

        val filtered = if (processFilter.isBlank()) snapshot.processes
        else snapshot.processes.filter { p ->
            p.name.contains(processFilter, ignoreCase = true) ||
                p.pid.toString().contains(processFilter) ||
                p.cmd.any { it.contains(processFilter, ignoreCase = true) }
        }

        // Hard cap: a typical system has hundreds of processes, but the
        // scroll view only shows ~30-50 rows. Capping both the sort and the
        // table render to [tableCap] is the single biggest performance win.
        // Hard cap on what we render: a typical screen fits ~30-50 visible
        // rows in the scroll view. Iterating more than ~100 rows costs
        // several ms per draw on slow machines because each row triggers
        // 8-10 widget calls (text/formatBytes/status badge). The snapshot
        // itself may contain thousands of processes; we only show the top
        // 100 by the current sort key.
        val tableCap = 50
        val selector: (Process) -> Comparable<*> = when (processSortColumn) {
            0 -> { p -> p.pid }
            1 -> { p -> p.name.lowercase() }
            2 -> { p -> p.cpuUsage }
            3 -> { p -> p.memoryBytes }
            4 -> { p -> p.diskUsage.readBytes + p.diskUsage.writtenBytes }
            5 -> { p -> p.runTimeSeconds }
            else -> { p -> p.pid }
        }
        val sorted: List<Process> = if (filtered.size <= tableCap) {
            val cmp = compareBy(selector)
            val ordered = if (processSortAscending) cmp else cmp.reversed()
            filtered.sortedWith(ordered)
        } else {
            if (processSortColumn == 1) {
                val cmp = compareBy<Process> { it.name.lowercase() }
                val ordered = if (processSortAscending) cmp else cmp.reversed()
                filtered.sortedWith(ordered)
            } else {
                val sign = if (processSortAscending) 1 else -1
                filtered.sortedBy { sign * (selector(it) as Number).toDouble() }.take(tableCap)
            }
        }
        if (filtered.size > tableCap) {
            ImGui.textDisabled("showing top $tableCap of ${filtered.size} processes (filter to narrow)")
        }

        if (ImGui.beginTable("##procs", 8, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG or ImGuiTableFlags.SCROLL_Y or ImGuiTableFlags.SORTABLE or ImGuiTableFlags.SIZING_STRETCH_PROP)) {
            ImGui.tableSetupColumn("PID", ImGuiTableColumnFlags.WIDTH_FIXED, 70f)
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("CPU%", ImGuiTableColumnFlags.WIDTH_FIXED, 70f)
            ImGui.tableSetupColumn("Memory", ImGuiTableColumnFlags.WIDTH_FIXED, 110f)
            ImGui.tableSetupColumn("Status", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
            ImGui.tableSetupColumn("User", ImGuiTableColumnFlags.WIDTH_FIXED, 80f)
            ImGui.tableSetupColumn("Threads", ImGuiTableColumnFlags.WIDTH_FIXED, 60f)
            ImGui.tableSetupColumn("Runtime", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableHeadersRow()

            // 7 widget calls per row × 100 rows = 700 calls. On slow CPUs
            // this dominates the draw; the columns that were dropped
            // (Disk I/O) used two formatBytes calls each, which were the
            // single most expensive text in the table.
            sorted.forEach { p ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0); ImGui.text(p.pid.toString())
                ImGui.tableSetColumnIndex(1); ImGui.text(p.name.ifEmpty { "?" })
                ImGui.tableSetColumnIndex(2); ImGui.text(fmtFloat1(p.cpuUsage))
                ImGui.tableSetColumnIndex(3); ImGui.text(formatBytes(p.memoryBytes))
                ImGui.tableSetColumnIndex(4); statusBadge(p.status)
                ImGui.tableSetColumnIndex(5); ImGui.text(p.userId?.takeIf { it.isNotBlank() } ?: "-")
                ImGui.tableSetColumnIndex(6); ImGui.text(p.tasks.size.toString())
                ImGui.tableSetColumnIndex(7); ImGui.text(formatDuration(p.runTimeSeconds))
            }
            ImGui.endTable()
        }



        // The per-process detail panel was removed because it added ~20
        // text() calls (plus a textWrapped) every frame, which is the
        // single most expensive widget group on the Processes tab. Use
        // a separate Sysinfo / Process detail view (e.g. `ps -p $PID`)
        // if you need the full info.
    }

    private fun drawDisks(snapshot: SystemSnapshot) {
        val newFilter = ImGui.inputText("Filter (mount / name)", diskFilter)
        if (newFilter != null) diskFilter = newFilter
        val list = if (diskFilter.isBlank()) snapshot.disks
        else snapshot.disks.filter {
            it.mountPoint.contains(diskFilter, true) || it.name.contains(diskFilter, true)
        }

        if (ImGui.beginTable("##disks", 7, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG or ImGuiTableFlags.SIZING_STRETCH_PROP)) {
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("Mount", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("FS", ImGuiTableColumnFlags.WIDTH_FIXED, 80f)
            ImGui.tableSetupColumn("Kind", ImGuiTableColumnFlags.WIDTH_FIXED, 60f)
            ImGui.tableSetupColumn("Used / Total", ImGuiTableColumnFlags.WIDTH_FIXED, 200f)
            ImGui.tableSetupColumn("Read", ImGuiTableColumnFlags.WIDTH_FIXED, 110f)
            ImGui.tableSetupColumn("Written", ImGuiTableColumnFlags.WIDTH_FIXED, 110f)
            ImGui.tableHeadersRow()
            list.forEach { d ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0); ImGui.text(d.name)
                ImGui.tableSetColumnIndex(1); ImGui.text(d.mountPoint)
                ImGui.tableSetColumnIndex(2); ImGui.text(d.fileSystem)
                ImGui.tableSetColumnIndex(3); ImGui.text("${d.kind}${if (d.readOnly) " (RO)" else ""}")
                ImGui.tableSetColumnIndex(4)
                val total = d.totalSpaceBytes
                val used = total - d.availableSpaceBytes
                ImGui.text("${formatBytes(used)} / ${formatBytes(total)}")
                if (total > 0uL) progressInline(used.toFloat() / total.toFloat())
                ImGui.tableSetColumnIndex(5); ImGui.text(formatBytes(d.usage.readBytes))
                ImGui.tableSetColumnIndex(6); ImGui.text(formatBytes(d.usage.writtenBytes))
            }
            ImGui.endTable()
        }
    }

    private fun drawNetwork(snapshot: SystemSnapshot) {
        val newFilter = ImGui.inputText("Filter (interface / ip)", networkFilter)
        if (newFilter != null) networkFilter = newFilter
        val list = if (networkFilter.isBlank()) snapshot.networks
        else snapshot.networks.filter {
            it.name.contains(networkFilter, true) ||
                it.ipAddresses.any { ip -> ip.contains(networkFilter, true) }
        }

        if (ImGui.beginTable("##net", 9, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG or ImGuiTableFlags.SIZING_STRETCH_PROP)) {
            ImGui.tableSetupColumn("Interface", ImGuiTableColumnFlags.WIDTH_FIXED, 110f)
            ImGui.tableSetupColumn("State", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
            ImGui.tableSetupColumn("MAC", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("MTU", ImGuiTableColumnFlags.WIDTH_FIXED, 70f)
            ImGui.tableSetupColumn("RX (delta/total)", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("TX (delta/total)", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("RX pkts", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableSetupColumn("TX pkts", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableSetupColumn("Errors", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableHeadersRow()
            list.forEach { n ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0); ImGui.text(n.name)
                ImGui.tableSetColumnIndex(1); ImGui.text(n.operationalState.name)
                ImGui.tableSetColumnIndex(2); ImGui.text(n.macAddress.ifEmpty { "-" })
                ImGui.tableSetColumnIndex(3); ImGui.text(n.mtuBytes.toString())
                ImGui.tableSetColumnIndex(4)
                ImGui.text("${formatBytes(n.receivedBytes)} / ${formatBytes(n.totalReceivedBytes)}")
                ImGui.tableSetColumnIndex(5)
                ImGui.text("${formatBytes(n.transmittedBytes)} / ${formatBytes(n.totalTransmittedBytes)}")
                ImGui.tableSetColumnIndex(6)
                ImGui.text("${n.packetsReceived} / ${n.totalPacketsReceived}")
                ImGui.tableSetColumnIndex(7)
                ImGui.text("${n.packetsTransmitted} / ${n.totalPacketsTransmitted}")
                ImGui.tableSetColumnIndex(8)
                ImGui.text("R ${n.errorsOnReceived} / T ${n.errorsOnTransmitted}")
            }
            ImGui.endTable()
        }

        if (list.isNotEmpty()) {
            ImGui.spacing()
            val selected = list.first()
            ImGui.separatorText("IPs for ${selected.name}")
            if (selected.ipAddresses.isEmpty()) {
                ImGui.textDisabled("(no IP addresses)")
            } else {
                selected.ipAddresses.forEach { ip -> ImGui.bulletText(ip) }
            }
        }
    }

    private fun drawSensors(snapshot: SystemSnapshot) {
        if (snapshot.components.isEmpty()) {
            ImGui.textDisabled("No temperature sensors reported by the kernel.")
            return
        }
        if (ImGui.beginTable("##sensors", 4, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG)) {
            ImGui.tableSetupColumn("Label", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("Temp", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
            ImGui.tableSetupColumn("Max", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
            ImGui.tableSetupColumn("Critical", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
            ImGui.tableHeadersRow()
            snapshot.components.forEach { c ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0)
                ImGui.text(c.label + (c.id?.let { " ($it)" } ?: ""))
                ImGui.tableSetColumnIndex(1)
                ImGui.text(c.temperatureCelsius?.let { "${fmtTemp(it)} °C" } ?: "-")
                ImGui.tableSetColumnIndex(2)
                ImGui.text(c.maxCelsius?.let { "${fmtTemp(it)} °C" } ?: "-")
                ImGui.tableSetColumnIndex(3)
                ImGui.text(c.criticalCelsius?.let { "${fmtTemp(it)} °C" } ?: "-")
            }
            ImGui.endTable()
        }

        val labels = snapshot.components.map { it.label }
        val temps = snapshot.components.map { it.temperatureCelsius ?: 0f }
        if (ImPlot.beginPlot("Temperatures (°C)", ImVec2(-1f, 220f))) {
            ImPlot.setupAxes("sensor", "°C", ImPlotFlags.NONE, ImPlotFlags.NONE)
            val xs = FloatArray(labels.size) { it.toFloat() }
            ImPlot.plotBars("Temp", xs, temps.toFloatArray(), 0.6, ImPlotSpec())
            ImPlot.endPlot()
        }
    }

    private fun drawUsers(snapshot: SystemSnapshot) {
        if (ImGui.beginTable("##users", 3, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG)) {
            ImGui.tableSetupColumn("User", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableSetupColumn("UID:GID", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
            ImGui.tableSetupColumn("Groups", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableHeadersRow()
            snapshot.users.forEach { u ->
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0); ImGui.text(u.name.ifEmpty { "?" })
                ImGui.tableSetColumnIndex(1); ImGui.text("${u.id}:${u.groupId}")
                ImGui.tableSetColumnIndex(2); ImGui.text(u.groups.joinToString { it.name })
            }
            ImGui.endTable()
        }
        ImGui.spacing()
        if (ImGui.collapsingHeader("Groups (${snapshot.groups.size})")) {
            if (ImGui.beginTable("##groups", 2, ImGuiTableFlags.BORDERS or ImGuiTableFlags.ROW_BG)) {
                ImGui.tableSetupColumn("GID", ImGuiTableColumnFlags.WIDTH_FIXED, 100f)
                ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WIDTH_STRETCH)
                ImGui.tableHeadersRow()
                snapshot.groups.forEach { g ->
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0); ImGui.text(g.id)
                    ImGui.tableSetColumnIndex(1); ImGui.text(g.name)
                }
                ImGui.endTable()
            }
        }
    }

    private fun drawSystem(snapshot: SystemSnapshot) {
        if (ImGui.beginTable("##sysinfo", 2, ImGuiTableFlags.BORDERS)) {
            ImGui.tableSetupColumn("Field", ImGuiTableColumnFlags.WIDTH_FIXED, 180f)
            ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableHeadersRow()
            row("Host", snapshot.hostName ?: "-")
            row("OS", snapshot.osName ?: "-")
            row("OS version", snapshot.osVersion ?: "-")
            row("Long OS version", snapshot.longOsVersion ?: "-")
            row("Kernel", snapshot.kernelVersion ?: "-")
            row("Kernel (long)", snapshot.kernelLongVersion)
            row("Distribution ID", snapshot.distributionId)
            row("Distribution like", snapshot.distributionIdLike.joinToString().ifEmpty { "-" })
            row("CPU arch", snapshot.cpuArch)
            row("Physical cores", snapshot.physicalCoreCount?.toString() ?: "-")
            row("Logical cores", snapshot.cpus.size.toString())
            row("Uptime", formatUptime(snapshot.uptimeSeconds))
            row("Boot time", snapshot.bootTimeSeconds.toString())
            row("Open files limit", snapshot.openFilesLimit?.toString() ?: "-")
            ImGui.endTable()
        }
        ImGui.spacing()
        ImGui.text("Motherboard")
        if (ImGui.beginTable("##mb", 2, ImGuiTableFlags.BORDERS)) {
            ImGui.tableSetupColumn("Field", ImGuiTableColumnFlags.WIDTH_FIXED, 180f)
            ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableHeadersRow()
            val mb = snapshot.motherboard
            row("Name", mb?.name ?: "-")
            row("Vendor", mb?.vendorName ?: "-")
            row("Version", mb?.version ?: "-")
            row("Serial", mb?.serialNumber ?: "-")
            row("Asset tag", mb?.assetTag ?: "-")
            ImGui.endTable()
        }
        ImGui.spacing()
        ImGui.text("Product")
        if (ImGui.beginTable("##prod", 2, ImGuiTableFlags.BORDERS)) {
            ImGui.tableSetupColumn("Field", ImGuiTableColumnFlags.WIDTH_FIXED, 180f)
            ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WIDTH_STRETCH)
            ImGui.tableHeadersRow()
            val p = snapshot.product
            row("Name", p?.name ?: "-")
            row("Family", p?.family ?: "-")
            row("Vendor", p?.vendorName ?: "-")
            row("Version", p?.version ?: "-")
            row("SKU", p?.stockKeepingUnit ?: "-")
            row("UUID", p?.uuid ?: "-")
            row("Serial", p?.serialNumber ?: "-")
            ImGui.endTable()
        }
    }

    // ============= helpers =============

    private fun row(label: String, value: String) {
        ImGui.tableNextRow()
        ImGui.tableSetColumnIndex(0); ImGui.text(label)
        ImGui.tableSetColumnIndex(1); ImGui.text(value)
    }

    private fun headline(label: String, value: String) {
        ImGui.textDisabled(label.uppercase())
        ImGui.text(value)
    }

    private fun statusBadge(status: ProcessStatus) {
        val col = when (status) {
            ProcessStatus.Run -> ImVec4(0.2f, 0.7f, 0.3f, 1f)
            ProcessStatus.Sleep -> ImVec4(0.4f, 0.6f, 0.8f, 1f)
            ProcessStatus.Zombie, ProcessStatus.Dead -> ImVec4(0.85f, 0.25f, 0.25f, 1f)
            ProcessStatus.Stop -> ImVec4(0.85f, 0.6f, 0.2f, 1f)
            else -> ImVec4(0.5f, 0.5f, 0.5f, 1f)
        }
        ImGui.pushStyleColor(ImGuiCol.TEXT, col)
        ImGui.text(status.name)
        ImGui.popStyleColor()
    }

    private fun bar(label: String, used: ULong, total: ULong) {
        ImGui.text(label)
        val frac = if (total > 0uL) (used.toDouble() / total.toDouble()).toFloat() else 0f
        progressInline(frac)
        ImGui.sameLine()
        ImGui.text("${formatBytes(used)} / ${formatBytes(total)}")
    }

    private fun progressInline(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        ImGui.progressBar(f, ImVec2(ImGui.getColumnWidth() * 0.6f, 0f))
    }

    private fun pct(num: ULong, denom: ULong): Float =
        if (denom > 0uL) (num.toDouble() / denom.toDouble() * 100.0).toFloat() else 0f
}

private enum class Section(val label: String) {
    OVERVIEW("Overview"),
    CPU("CPU"),
    MEMORY("Memory"),
    PROCESSES("Processes"),
    DISKS("Disks"),
    NETWORK("Network"),
    SENSORS("Sensors"),
    USERS("Users"),
    SYSTEM("System");

    companion object {
        val entries: List<Section> = values().toList()
    }
}

package cn.enaium.sysinfomonitor

import cn.enaium.sysinfo.Components
import cn.enaium.sysinfo.Cpu
import cn.enaium.sysinfo.Disks
import cn.enaium.sysinfo.Groups
import cn.enaium.sysinfo.Motherboard
import cn.enaium.sysinfo.Networks
import cn.enaium.sysinfo.Product
import cn.enaium.sysinfo.System
import cn.enaium.sysinfo.Users

/**
 * Live system snapshot. Pure data; no native handles escape the
 * [System]/[Disks]/[Networks]/[Components]/[Users] lifetime because
 * [SnapshotManager.refresh] rebuilds the snapshot in-place and the
 * underlying sysinfo handles are kept in long-lived fields.
 */
class SystemSnapshot(
    var hostName: String?,
    var osName: String?,
    var osVersion: String?,
    var longOsVersion: String?,
    var kernelVersion: String?,
    var kernelLongVersion: String,
    var distributionId: String,
    var distributionIdLike: List<String>,
    var cpuArch: String,
    var physicalCoreCount: Int?,
    var uptimeSeconds: ULong,
    var bootTimeSeconds: ULong,
    var loadAverage: cn.enaium.sysinfo.LoadAvg?,
    var cgroupLimits: cn.enaium.sysinfo.CGroupLimits?,
    var openFilesLimit: Int?,
    var totalMemory: ULong,
    var usedMemory: ULong,
    var availableMemory: ULong,
    var freeMemory: ULong,
    var totalSwap: ULong,
    var usedSwap: ULong,
    var freeSwap: ULong,
    var globalCpuUsage: Float,
    var minimumCpuUpdateIntervalMs: ULong,
    var cpus: List<Cpu>,
    var processes: List<cn.enaium.sysinfo.Process>,
    var disks: List<cn.enaium.sysinfo.Disk>,
    var networks: List<cn.enaium.sysinfo.NetworkInterface>,
    var components: List<cn.enaium.sysinfo.Component>,
    var users: List<cn.enaium.sysinfo.UserInfo>,
    var groups: List<cn.enaium.sysinfo.GroupInfo>,
    var motherboard: cn.enaium.sysinfo.MotherboardInfo?,
    var product: cn.enaium.sysinfo.ProductInfo?,
)

/**
 * Owns the live sysinfo-kmp handles and rebuilds the [SystemSnapshot] in
 * place on every [refresh] call.
 *
 * Sysinfo's [System.refreshAll] walks the entire process table and reads
 * per-process disk I/O counters. On a system with 1000+ live processes
 * this can take 100-900 ms — measured locally at ~950ms for 3000
 * processes, ~150ms for `refreshCpu+refreshMemory+counter refreshes`
 * alone. Blocking the UI thread for that long makes every interaction
 * feel laggy, so we amortize the work:
 *
 *  - **Cheap path** (every call): [System.refreshCpu] +
 *    [System.refreshMemory]. CPU deltas + memory totals.
 *  - **Expensive path** (every [processRefreshEvery] calls):
 *    [System.refreshAll] + disks/networks/sensors/users/groups
 *    refreshes + the process list re-read.
 *
 * The previous snapshot's process/disk/network/etc. lists are reused on
 * the cheap path so the UI never goes blank — the counters just update
 * a little less often. Default is 1 Hz for the full refresh at 4 Hz
 * data rate, which is plenty for a human observer.
 */
class SnapshotManager(
    /** Number of [refresh] calls between expensive process-table re-reads. */
    private val processRefreshEvery: Int = 16,
) {

    private val system = System(newAll = true)
    private val disks = Disks()
    private val networks = Networks()
    private val components = Components()
    private val users = Users()
    private val groups = Groups()

    /** A single snapshot, mutated in place. Null until the first [refresh]. */
    var current: SystemSnapshot? = null
        private set

    /** Number of successful refreshes (monotonic counter). */
    var tick: ULong = 0u
        private set

    fun refresh(): SystemSnapshot {
        val fullProcess = tick % processRefreshEvery.toULong() == 0uL
        if (fullProcess) {
            // Expensive: walks the process table and reads per-process
            // disk I/O counters. Run only every processRefreshEvery calls.
            system.refreshAll()
            disks.refresh()
            networks.refresh()
            components.refresh()
            users.refresh()
            groups.refresh()
        } else {
            // Cheap: just CPU deltas and memory totals. The previous
            // process/disk/network/etc. lists are reused, so the UI
            // keeps showing the last-known state.
            system.refreshCpu()
            system.refreshMemory()
        }

        val cpus = system.cpus
        val processes = if (fullProcess) {
            system.processes().sortedByDescending { it.cpuUsage }
        } else {
            current?.processes ?: emptyList()
        }
        val disksList = disks.list
        val networksList = networks.list
        val componentsList = components.list
        val usersList = users.list
        val groupsList = groups.list

        val snap = current ?: SystemSnapshot(
            hostName = null, osName = null, osVersion = null, longOsVersion = null,
            kernelVersion = null, kernelLongVersion = "", distributionId = "",
            distributionIdLike = emptyList(), cpuArch = "", physicalCoreCount = null,
            uptimeSeconds = 0u, bootTimeSeconds = 0u, loadAverage = null,
            cgroupLimits = null, openFilesLimit = null, totalMemory = 0u, usedMemory = 0u,
            availableMemory = 0u, freeMemory = 0u, totalSwap = 0u, usedSwap = 0u,
            freeSwap = 0u, globalCpuUsage = 0f, minimumCpuUpdateIntervalMs = 0u,
            cpus = emptyList(), processes = emptyList(), disks = emptyList(),
            networks = emptyList(), components = emptyList(), users = emptyList(),
            groups = emptyList(), motherboard = null, product = null,
        ).also { current = it }

        snap.hostName = System.hostName()
        snap.osName = System.name()
        snap.osVersion = System.osVersion()
        snap.longOsVersion = System.longOsVersion()
        snap.kernelVersion = System.kernelVersion()
        snap.kernelLongVersion = System.kernelLongVersion()
        snap.distributionId = System.distributionId()
        snap.distributionIdLike = System.distributionIdLike()
        snap.cpuArch = System.cpuArch()
        snap.physicalCoreCount = System.physicalCoreCount()
        snap.uptimeSeconds = System.uptime()
        snap.bootTimeSeconds = System.bootTime()
        snap.loadAverage = System.loadAverage()
        snap.cgroupLimits = System.cgroupLimits()
        snap.openFilesLimit = System.openFilesLimit()
        snap.totalMemory = system.totalMemory
        snap.usedMemory = system.usedMemory
        snap.availableMemory = system.availableMemory
        snap.freeMemory = system.freeMemory
        snap.totalSwap = system.totalSwap
        snap.usedSwap = system.usedSwap
        snap.freeSwap = system.freeSwap
        snap.globalCpuUsage = system.globalCpuUsage
        snap.minimumCpuUpdateIntervalMs = System.minimumCpuUpdateIntervalMs()
        snap.cpus = cpus
        snap.processes = processes
        snap.disks = disksList
        snap.networks = networksList
        snap.components = componentsList
        snap.users = usersList
        snap.groups = groupsList
        snap.motherboard = Motherboard.info()
        snap.product = Product.info()

        tick += 1u
        return snap
    }

    fun close() {
        system.close()
        disks.close()
        networks.close()
        components.close()
        users.close()
        groups.close()
    }
}

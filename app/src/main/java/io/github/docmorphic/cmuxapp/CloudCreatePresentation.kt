package io.github.docmorphic.cmuxapp

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

internal class CloudCreatePresentation(val catalog: CloudMachineCatalog,
    private val formatList: (List<String>) -> String = ::cloudPlanList) {
    val kind = if (catalog.availableKinds?.contains(CloudMachineKind.DESKTOP) == false) CloudMachineKind.BASE else CloudMachineKind.DESKTOP
    val sizes = catalog.limits?.memoryOptionsMb.orEmpty().filter { diskMb(it) != null }.distinct().sorted().ifEmpty { listOf(8192) }
    val defaultMemory = 8192.takeIf { it in sizes } ?: sizes.first()
    val lockedSizes = catalog.limits?.lockedMemoryOptionsMb.orEmpty().filter { diskMb(it) != null }.distinct().sorted()
    fun upgradePlan(memoryMb: Int) = (catalog.limits?.memoryUpgradePlansByMb?.get(memoryMb.toString())
        ?: catalog.limits?.memoryUpgradePlanId)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    private val upgradePlans get() = lockedSizes.mapNotNull(::upgradePlan).distinct()
    val preferredUpgradePlan: String? get() = upgradePlans
        .maxByOrNull { when (it) { "max" -> 2; "pro" -> 1; else -> 0 } }
    private fun planName(plan: String) = plan.split(' ').joinToString(" ") { it.replaceFirstChar { letter -> letter.titlecase(Locale.ROOT) } }
    fun planLabel(memoryMb: Int) = upgradePlan(memoryMb)?.let(::planName) ?: "an upgraded plan"
    fun lockedLabel(memoryMb: Int) = upgradePlan(memoryMb)?.let { "${label(memoryMb)} · Requires ${planName(it)}" } ?: label(memoryMb)
    val lockedSizesNote: String? get() {
        val sizes = lockedSizes.filter { upgradePlan(it) != null }.map { "${it / 1024} GB" }
        if (sizes.isEmpty() || upgradePlans.isEmpty()) return null
        return "${formatList(sizes)} machines need cmux ${formatList(upgradePlans.map(::planName))}."
    }
    val upgradeActionTitle: String? get() = upgradePlans.takeIf { it.isNotEmpty() }
        ?.let { "Upgrade to ${formatList(it.map(::planName))}" }
    fun label(memoryMb: Int) = "${memoryMb / 1024} GB RAM · ${(diskMb(memoryMb) ?: memoryMb) / 1024} GB disk"
    fun options(memoryMb: Int): CloudMachineCreateOptions {
        require(memoryMb in sizes) { "Choose an available machine size" }
        return CloudMachineCreateOptions(kind = kind, memoryMb = memoryMb)
    }
    val machineUsage: String? get() = catalog.limits?.let { limits ->
        val active = limits.activeMachineCount ?: catalog.machines.count { it.lifecycle in setOf(CloudMachineLifecycle.RUNNING, CloudMachineLifecycle.PROVISIONING) }
        limits.maxActiveMachines?.let { "$active of $it machines in use" } ?: "$active machines in use"
    }
    val poolUsage: String? get() = catalog.limits?.pool?.let {
        "${it.usedVcpus} of ${it.vcpus} vCPUs · ${it.usedMemoryMb / 1024} of ${it.memoryMb / 1024} GB RAM in use"
    }
    companion object {
        fun diskMb(memoryMb: Int): Int? = when (memoryMb) {
            4096 -> 16384; 8192 -> 32768; 16384 -> 65536; 24576 -> 98304
            32768, 65536 -> 131072; else -> null
        }
    }
}

/** English fallback for the app's current English copy; Android supplies its
 * locale-aware ICU formatter at the presentation boundary. */
private fun cloudPlanList(values: List<String>): String = when (values.size) {
    0 -> ""
    1 -> values.single()
    2 -> values.joinToString(" and ")
    else -> values.dropLast(1).joinToString(", ") + ", and " + values.last()
}

/** The sideloaded build uses the upstream non-StoreKit destination. Server plan
 * identifiers are query values; they cannot replace the destination or add keys. */
internal fun cloudPlansUrl(plan: String?): String = "https://cmux.com/pricing".toHttpUrl().newBuilder().apply {
    plan?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("plan", it) }
}.build().toString()

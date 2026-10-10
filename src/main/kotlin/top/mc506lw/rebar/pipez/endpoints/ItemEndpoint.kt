package top.mc506lw.rebar.pipez.endpoints

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.InteractRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.TickingRebarBlock
import io.github.pylonmc.rebar.event.api.annotation.MultiHandler
import io.github.pylonmc.rebar.item.RebarItem
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.PipezKeys
import top.mc506lw.rebar.pipez.bridge.VirtualInventoryBridge
import top.mc506lw.rebar.pipez.conduit.CARDINAL_FACES
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.ConduitGraph
import top.mc506lw.rebar.pipez.conduit.PipeType
import top.mc506lw.rebar.pipez.conduit.rebarAt
import top.mc506lw.rebar.pipez.items.WrenchItem
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.UpdateReason

/**
 * 物品端点 —— 设计文档里"重的那一半"。
 *
 * ## 为什么端点可以 tick，管段不行
 *
 * 搬运、过滤、找目标这些都是**持续性的工作**，只能挂在少数端点上。
 * 所以端点实现 [TickingRebarBlock]（默认 1 tick 一次），管段一个 tick 都不花。
 *
 * ## 容器怎么取
 *
 * 刻意**不用** Rebar 的 `LogisticSlot` / `LogisticGroup`：那套 API 在 release 版里
 * 从 Kotlin 侧取不到（和 `FluidManager` 一样的问题）。这里只用两条公开路径：
 *
 * - Rebar 机器 → [VirtualInventoryRebarBlock] 暴露的 `VirtualInventory`（InvUI，本来就是依赖）
 * - 原版容器 → `InventoryHolder.inventory`
 *
 * ## 路由
 *
 * 用 [ConduitGraph] 自己做 BFS，**不用 `CargoRoutes`** ——
 * 后者会把管段链上的第一根 `CargoRebarBlock` 当成终点。目标每 20 tick 重算一次。
 *
 * ## 用法
 *
 * - 扳手右键 → 循环切换 NONE / EXTRACT / INSERT
 * - 潜行 + 右键（拿任意物品）→ 把该物品加入 / 移出白名单
 * - 白名单为空 = 全部放行
 */
class ItemEndpoint : RebarBlock, TickingRebarBlock, InteractRebarBlockHandler {

    var mode: EndpointMode = EndpointMode.NONE
        private set

    private val filter = LinkedHashSet<Material>()

    private var cachedTarget: ItemEndpoint? = null
    private var cacheTicks = 0

    constructor(block: Block, context: BlockCreateContext) : super(block, context)

    constructor(block: Block, pdc: PersistentDataContainer) : super(block, pdc) {
        mode = EndpointMode.fromOrdinal(pdc.get(PipezKeys.ENDPOINT_MODE, PersistentDataType.BYTE)?.toInt() ?: 0)
        pdc.get(PipezKeys.ENDPOINT_FILTER, PersistentDataType.STRING)
            ?.split(',')
            ?.mapNotNull { name -> runCatching { Material.valueOf(name) }.getOrNull() }
            ?.let(filter::addAll)
    }

    override fun write(pdc: PersistentDataContainer) {
        super.write(pdc)
        if (mode != EndpointMode.NONE) {
            pdc.set(PipezKeys.ENDPOINT_MODE, PersistentDataType.BYTE, mode.ordinal.toByte())
        }
        if (filter.isNotEmpty()) {
            pdc.set(PipezKeys.ENDPOINT_FILTER, PersistentDataType.STRING, filter.joinToString(",") { it.name })
        }
    }

    // ---------------------------------------------------------------- 交互

    @MultiHandler(priorities = [EventPriority.HIGH])
    override fun onInteractedWith(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return

        if (RebarItem.isRebarItem(item, WrenchItem::class.java)) {
            event.isCancelled = true
            cycleMode()
            return
        }

        if (event.player.isSneaking) {
            event.isCancelled = true
            toggleFilter(item.type)
        }
    }

    fun cycleMode() {
        mode = mode.next
        invalidateTarget()
    }

    /** 返回 true 表示加进了白名单，false 表示移出了。 */
    fun toggleFilter(material: Material): Boolean {
        val added = if (material in filter) {
            filter.remove(material)
            false
        } else {
            filter.add(material)
            true
        }
        return added
    }

    fun filterSnapshot(): Set<Material> = filter.toSet()

    // ---------------------------------------------------------------- 搬运

    override fun tick() {
        if (mode != EndpointMode.EXTRACT) return

        val sourceSlots = slotsOf(containerBlock() ?: return)
        if (sourceSlots.isEmpty()) return

        val target = resolveTarget() ?: return
        val targetSlots = slotsOf(target.containerBlock() ?: return)
        if (targetSlots.isEmpty()) return

        var budget = PipezConfig.itemsPerTick
        for (slot in sourceSlots) {
            if (budget <= 0) break
            val stack = slot.get() ?: continue
            if (stack.type == Material.AIR || stack.amount <= 0) continue
            if (filter.isNotEmpty() && stack.type !in filter) continue

            for (targetSlot in targetSlots) {
                val moved = move(slot, targetSlot, budget)
                if (moved > 0) {
                    budget -= moved
                    break
                }
            }
        }
    }

    /** 搬运一次；返回实际搬了几个。 */
    private fun move(from: TransferSlot, to: TransferSlot, budget: Int): Int {
        val stack = from.get() ?: return 0
        if (stack.type == Material.AIR || stack.amount <= 0) return 0

        val existing = to.get()
        val current = if (existing == null || existing.type == Material.AIR) 0 else existing.amount
        if (current > 0 && !existing!!.isSimilar(stack)) return 0

        val room = stack.maxStackSize - current
        if (room <= 0) return 0

        val amount = minOf(budget, room, stack.amount)
        if (amount <= 0) return 0

        from.set(if (amount == stack.amount) null else stack.asQuantity(stack.amount - amount))
        to.set(stack.asQuantity(current + amount))
        return amount
    }

    // ---------------------------------------------------------------- 容器

    /** 相邻的第一个容器（排除管段与其它端点）。 */
    fun containerBlock(): Block? =
        CARDINAL_FACES.asSequence()
            .map { block.getRelative(it) }
            .firstOrNull(::isContainer)

    private fun isContainer(candidate: Block): Boolean {
        val rebar = rebarAt(candidate)
        if (rebar is ConduitBlock || rebar is ItemEndpoint) return false
        if (rebar != null && VirtualInventoryBridge.inventoriesOf(rebar).isNotEmpty()) return true
        return candidate.state is InventoryHolder
    }

    /**
     * 取一个方块的可搬运槽位。
     *
     * 两条路：
     * - **Rebar 机器** → 反射拿 `VirtualInventory`（InvUI 的公开类），之后读写全走公开 API
     * - **原版容器** → `InventoryHolder.inventory`
     *
     * Rebar 自己的 `LogisticSlot` / `LogisticGroup` 在 release 版里从 Kotlin 侧取不到，
     * 所以刻意不用它们。
     */
    private fun slotsOf(target: Block): List<TransferSlot> {
        val rebar = rebarAt(target)
        if (rebar != null) {
            val virtual = VirtualInventoryBridge.inventoriesOf(rebar)
            if (virtual.isNotEmpty()) {
                val slots = virtual.values.flatMap { inventory ->
                    (0 until inventory.size).map { VirtualSlot(inventory, it) }
                }
                if (slots.isNotEmpty()) return slots
            }
        }

        val holder = target.state as? InventoryHolder ?: return emptyList()
        val inventory = holder.inventory
        return (0 until inventory.size).map { BukkitSlot(inventory, it) }
    }

    // ---------------------------------------------------------------- 目标

    /**
     * 找下游的 INSERT 端点。
     *
     * 每 [TARGET_CACHE_TICKS] tick 重算一次，避免每 tick 跑一次 BFS；
     * 网络变动最坏就是慢这么多 tick 才生效。
     *
     * ⚠️ 只沿 **`Category.ITEM`** 的管段走 —— 也就是"物品管"。
     * 物品管目前还没做（`PipeType` 里没有对应条目），所以这个 BFS
     * **暂时找不到任何目标**。这是有意的：物品端点不会顺着能量管 / 流体管乱跑
     * （用户："能量管和物品、流体管道也不自动连接"）。等物品管做出来，
     * 这里一行都不用改，自然就通了。
     */
    private fun resolveTarget(): ItemEndpoint? {
        if (cacheTicks++ % TARGET_CACHE_TICKS != 0) return cachedTarget

        cachedTarget = ConduitGraph.findFirst(block, PipeType.Category.ITEM) { candidate ->
            candidate is ItemEndpoint && candidate !== this && candidate.mode == EndpointMode.INSERT
        } as? ItemEndpoint
        return cachedTarget
    }

    fun invalidateTarget() {
        cachedTarget = null
        cacheTicks = 0
    }

    // ---------------------------------------------------------------- 槽位抽象

    /** 一个可读写的槽位。只为抹平原版 Inventory 与 InvUI VirtualInventory 的差异。 */
    private interface TransferSlot {
        fun get(): ItemStack?
        fun set(stack: ItemStack?)
    }

    private class BukkitSlot(private val inventory: Inventory, private val index: Int) : TransferSlot {
        override fun get(): ItemStack? = inventory.getItem(index)
        override fun set(stack: ItemStack?) {
            inventory.setItem(index, stack)
        }
    }

    /**
     * InvUI `VirtualInventory` 的槽位。
     *
     * 写入用 `setItem(UpdateReason, slot, stack)` —— 注意参数顺序是
     * `(原因, 槽位, 物品)`，不是 `(槽位, 物品)`。
     * 用 `SUPPRESSED` 是因为管道搬运不该触发玩家的 pre-update 拦截。
     */
    private class VirtualSlot(
        private val inventory: VirtualInventory,
        private val index: Int
    ) : TransferSlot {
        override fun get(): ItemStack? =
            inventory.getItem(index)?.takeIf { it.type != Material.AIR }

        override fun set(stack: ItemStack?) {
            inventory.setItem(UpdateReason.SUPPRESSED, index, stack)
        }
    }

    private companion object {
        const val TARGET_CACHE_TICKS = 20
    }
}

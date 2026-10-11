package top.mc506lw.rebar.pipez.cargo

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import io.github.pylonmc.rebar.util.position.position
import top.mc506lw.rebar.pipez.bridge.VirtualInventoryBridge
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.rebarAt
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.UpdateReason

/**
 * 一个"可搬运的容器" —— 物品管道两端的抽象。
 *
 * ## 为什么要有这一层
 *
 * 管道看到的容器有两种来源，读写 API 完全不同：
 * - **Rebar 机器** → [VirtualInventory]（InvUI 的类），写要用 `setItem(原因, 槽, 物品)`
 * - **原版容器** → `InventoryHolder.inventory`，写用 `setItem(槽, 物品)`
 *
 * 搬运逻辑不该关心这个差异，所以在这里抹平。
 *
 * ## 为什么不用 Rebar 的 `LogisticSlot`
 *
 * 虽然 `io.github.pylonmc.rebar.logistics.slot.LogisticSlot` 是公开接口，
 * 但要用它还得先把方块判成 `LogisticRebarBlock` / 走 `LogisticGroup.getVanillaLogisticSlots`，
 * 那套的槽位语义（input/output 分组）是给货运管道设计的，和这里"整箱搬运"的需求
 * 并不吻合；而且它同样绕不开虚拟库存的反射。这里只用两条实在的公开路径。
 */
class CargoEndpoint private constructor(
    val block: Block,
    private val slots: List<CargoSlot>
) {

    /** 槽位数量；0 表示这个容器空到没法搬。 */
    val size: Int get() = slots.size

    fun slots(): List<CargoSlot> = slots

    companion object {

        /**
         * 判断 [block] 能不能当一个搬运端点。
         *
         * 排除管道自己（管段不算容器），其余只要"有库存"就算：
         * - Rebar 机器 → 有虚拟库存
         * - 原版方块 → `InventoryHolder`
         */
        fun of(block: Block): CargoEndpoint? {
            if (!block.position.isChunkLoaded) return null
            val rebar = rebarAt(block)
            // 管道不是容器 —— 否则物品会在管段之间"互搬"
            if (rebar is ConduitBlock) return null

            if (rebar != null) {
                val virtual = VirtualInventoryBridge.inventoriesOf(rebar)
                if (virtual.isNotEmpty()) {
                    val slots = virtual.values.flatMap { inv ->
                        (0 until inv.size).map { VirtualCargoSlot(inv, it) }
                    }
                    if (slots.isNotEmpty()) return CargoEndpoint(block, slots)
                }
            }

            val holder = block.state as? InventoryHolder ?: return null
            val inventory = holder.inventory
            if (inventory.size <= 0) return null
            return CargoEndpoint(block, (0 until inventory.size).map { BukkitCargoSlot(inventory, it) })
        }

        /** 只做"是不是容器"的判断，不需要建槽位时用。 */
        fun isContainer(block: Block): Boolean {
            // ⚠️ 必须先查区块加载：`Block.getState()` 在未加载区块上会抛异常，
            // 而管道天生跨区块（邻居可能落在未加载的区块里）。
            // 未加载 → 当作"不是容器"，等区块加载后由 markAround 重算。
            if (!block.position.isChunkLoaded) return false
            val rebar = rebarAt(block)
            if (rebar is ConduitBlock) return false
            if (rebar != null && VirtualInventoryBridge.inventoriesOf(rebar).isNotEmpty()) return true
            return block.state is InventoryHolder
        }
    }
}

/**
 * 一个可读写的槽位。
 *
 * 刻意保持**最小接口**：只要 get / set / 容量，物品搬运任务用不着更多。
 */
interface CargoSlot {

    /** 当前物品；空格返回 null。 */
    fun get(): ItemStack?

    /** 写入；null = 清空。 */
    fun set(stack: ItemStack?)

    /** 这个槽位最多能装多少 [stack]（同物品追加时按剩余空间算）。 */
    fun roomFor(stack: ItemStack): Int {
        val existing = get()
        val current = if (existing == null || existing.type == Material.AIR) 0 else existing.amount
        if (current > 0 && !existing!!.isSimilar(stack)) return 0
        return (stack.maxStackSize - current).coerceAtLeast(0)
    }
}

/** 原版 `Inventory` 的槽位。 */
internal class BukkitCargoSlot(
    private val inventory: Inventory,
    private val index: Int
) : CargoSlot {

    override fun get(): ItemStack? =
        inventory.getItem(index)?.takeIf { it.type != Material.AIR }

    override fun set(stack: ItemStack?) {
        inventory.setItem(index, stack)
    }
}

/**
 * InvUI `VirtualInventory` 的槽位。
 *
 * ⚠️ 写入方法签名是 `setItem(UpdateReason, slot: Int, item: ItemStack?)` ——
 * **参数顺序是 (原因, 槽位, 物品)**，不是直觉上的 (槽位, 物品)。
 * 用 `UpdateReason.SUPPRESSED` 是因为管道搬运不该触发玩家侧 pre-update 事件。
 */
internal class VirtualCargoSlot(
    private val inventory: VirtualInventory,
    private val index: Int
) : CargoSlot {

    override fun get(): ItemStack? =
        inventory.getItem(index)?.takeIf { it.type != Material.AIR }

    override fun set(stack: ItemStack?) {
        inventory.setItem(UpdateReason.SUPPRESSED, index, stack)
    }
}

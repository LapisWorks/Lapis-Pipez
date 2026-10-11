package top.mc506lw.rebar.pipez.cargo

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.LogisticRebarBlock
import io.github.pylonmc.rebar.logistics.LogisticGroup
import io.github.pylonmc.rebar.logistics.LogisticGroupType
import io.github.pylonmc.rebar.logistics.slot.VirtualInventoryLogisticSlot
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
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
 * - **原版容器** → `InventoryHolder.inventory`，写用 `setItem(槽, 物品)`
 * - **Rebar 机器** → 虚拟库存（`VirtualInventory`），写要用 `setItem(原因, 槽, 物品)`
 *
 * 搬运逻辑不该关心这个差异，所以在这里抹平。
 *
 * ## ⚠️ 关键：一个容器分"抽得"和"插得"两侧，不是一坨槽位
 *
 * 最初的实现是**把所有虚拟库存拍平成一长条槽位，抽取时从头扫到尾** ——
 * 结果铁熔炉的**工厂升级槽**（`upgrade_red/green/blue`）和**待烧炼输入区**
 * （`input`）全被抽走了（用户实测）。原版熔炉同理：燃料槽、烧炼进料槽都被当成"可搬走的东西"。
 *
 * 现在按 **Rebar 的物流组语义**区分（[LogisticGroupType]）：
 * - **[LogisticGroupType.INPUT]**（机器**吃**进去的东西，如熔炉进料 / 燃料 / 升级模块）
 *   → **只能插，不能抽**。
 * - **[LogisticGroupType.OUTPUT]**（机器**吐**出来的东西，如熔炉成品）
 *   → **只能抽，不能插**。
 * - **[LogisticGroupType.BOTH]**（普通箱子：进可放、出可取）
 *   → 抽插都行。
 *
 * 这正是 Pipez 的语义：Pipez 拿的是机器暴露的 item capability，
 * **机器自己决定暴露哪些槽**；Rebar 机器用物流组表达同一件事。
 * 所以"抽取面"抽的是 output/both，"接收面"插的是 input/both。
 *
 * 没有声明物流组的方块（例如没实现 `LogisticRebarBlock` 的 Rebar 机器），
 * 退回成"所有虚拟库存都算 BOTH"的老行为 —— 总比搬不了强。
 *
 * ## 为什么不用 Rebar 的 `LogisticSlot` 直接读写
 *
 * `LogisticSlot` 是公开接口，但它用 `Long` 计数量（为超大容量设计），
 * 而且每种方块要单独走 `LogisticGroup.getVanillaLogisticSlots` / 方块自己的注册。
 * 我们对"整栈搬运"只需要 `Int` 数量的最简接口，且希望**同一套逻辑**同时覆盖
 * 原版容器与虚拟库存（含未声明物流组的机器）。所以这里只用
 * **[LogisticGroupType] 这一个语义判断**，读写仍走各自的原生 API。
 */
class CargoEndpoint private constructor(
    val block: Block,
    /** 可以**抽走**的槽位（机器的产出侧）。 */
    private val extractable: List<CargoSlot>,
    /** 可以**插入**的槽位（机器的输入侧）。 */
    private val insertable: List<CargoSlot>
) {

    /** 可抽取槽位数量；0 表示这个容器抽不出东西。 */
    val size: Int get() = extractable.size

    /** 能不能从这个容器抽东西。 */
    val canExtract: Boolean get() = extractable.isNotEmpty()

    /** 能不能往这个容器插东西。 */
    val canInsert: Boolean get() = insertable.isNotEmpty()

    /** 可抽取的槽位（抽取面用）。 */
    fun extractSlots(): List<CargoSlot> = extractable

    /** 可插入的槽位（接收面用）。 */
    fun insertSlots(): List<CargoSlot> = insertable

    companion object {

        /**
         * 判断 [block] 能不能当一个"抽取端点"的源 —— 也就是**有东西可抽**。
         *
         * 注意这不等于 [isContainer]：一个只有 INPUT 组的机器（纯进料口）**不能抽**，
         * 但它是合法容器（能插）。
         */
        fun of(block: Block): CargoEndpoint? {
            if (!block.position.isChunkLoaded) return null
            val rebar = rebarAt(block)
            // 管道不是容器 —— 否则物品会在管段之间"互搬"
            if (rebar is ConduitBlock) return null

            if (rebar != null) {
                val endpoint = ofRebar(rebar)
                if (endpoint != null) return endpoint
            }

            // 原版容器：整箱按 BOTH 处理（可以抽、可以插）
            val holder = block.state as? InventoryHolder ?: return null
            val inventory = holder.inventory
            if (inventory.size <= 0) return null
            val slots = (0 until inventory.size).map { BukkitCargoSlot(inventory, it) }
            return CargoEndpoint(block, slots, slots)
        }

        /** 只做"是不是容器"的判断（能抽或能插都算），不需要建槽位时用。 */
        fun isContainer(block: Block): Boolean {
            // ⚠️ 必须先查区块加载：`Block.getState()` 在未加载区块上会抛异常，
            // 而管道天生跨区块（邻居可能落在未加载的区块里）。
            // 未加载 → 当作"不是容器"，等区块加载后由 markAround 重算。
            if (!block.position.isChunkLoaded) return false
            val rebar = rebarAt(block)
            if (rebar is ConduitBlock) return false
            if (rebar != null && ofRebar(rebar) != null) return true
            return block.state is InventoryHolder
        }

        // ------------------------------------------------------------ Rebar 机器

        /**
         * 把一台 Rebar 机器拆成"可抽 / 可插"两侧。
         *
         * 优先走 [LogisticRebarBlock] 的物流组（语义最准）：
         * OUTPUT / BOTH 组可抽，INPUT / BOTH 组可插。
         *
         * 机器没声明物流组时，退回"所有虚拟库存都算 BOTH"（旧行为，保证能搬）。
         */
        private fun ofRebar(rebar: RebarBlock): CargoEndpoint? {
            val block = rebar.block

            if (rebar is LogisticRebarBlock) {
                val groups = runCatching { rebar.getLogisticGroups() }.getOrNull()
                if (!groups.isNullOrEmpty()) {
                    val extract = ArrayList<CargoSlot>()
                    val insert = ArrayList<CargoSlot>()
                    for (group in groups.values) {
                        val slots = slotsOf(group) ?: continue
                        when (group.slotType) {
                            LogisticGroupType.OUTPUT -> extract += slots
                            LogisticGroupType.INPUT -> insert += slots
                            LogisticGroupType.BOTH -> {
                                extract += slots
                                insert += slots
                            }
                        }
                    }
                    if (extract.isNotEmpty() || insert.isNotEmpty()) {
                        return CargoEndpoint(block, extract, insert)
                    }
                }
            }

            // 兜底：没声明物流组的机器 —— 所有虚拟库存一律 BOTH
            val virtual = VirtualInventoryBridge.inventoriesOf(rebar)
            if (virtual.isNotEmpty()) {
                val slots = ArrayList<CargoSlot>()
                for (inv in virtual.values) {
                    for (slot in 0 until inv.size) slots += VirtualCargoSlot(inv, slot)
                }
                if (slots.isNotEmpty()) return CargoEndpoint(block, slots, slots)
            }

            return null
        }

        /**
         * 从一个物流组里取槽位。
         *
         * ⚠️ **逐槽容错**：某个槽不是虚拟库存（比如原版方块的 `VanillaInventoryLogisticSlot`
         * / `FurnaceFuelLogisticSlot`）就**跳过那一个**，不整组丢弃 —— 否则一个组里
         * 混了一格原版槽就会把整组（可能含全部产出槽）废掉。一组一个槽都取不到才返回 null。
         *
         * ## 为什么直接 `is VirtualInventoryLogisticSlot`
         *
         * 我们只读写机器**虚拟库存**（`VirtualInventory`）。Rebar 给虚拟库存用的槽位类
         * 就是 [VirtualInventoryLogisticSlot]，**它是公开类、`inventory` / `slot` 都是公开
         * `val`**（源码在 `rebar/src/main/kotlin/.../logistics/slot/VirtualInventoryLogisticSlot.kt`），
         * 所以 `slot.inventory` 直接就能读 —— **不需要反射**。
         *
         * 这一点很重要：原先用反射去掏 private backing field，Rebar 一旦重命名就**静默失效**
         * （搬不动东西，且没有任何报错）；改成直接类型引用后，那种改动会在**编译期**就炸出来。
         *
         * 原版方块（`VanillaInventoryLogisticSlot` 等）不在这里处理 —— 那些方块走下面
         * "原版容器"兜底分支（整箱 BOTH），行为一致。
         */
        private fun slotsOf(group: LogisticGroup): List<CargoSlot>? {
            val result = ArrayList<CargoSlot>()
            for (slot in group.slots) {
                // 只有虚拟库存槽位能直接定位到 VirtualInventory + 槽号；其余（原版槽）跳过。
                // ⚠️ `VanillaInventoryLogisticSlot` 也有 `inventory`，但那是原版 `Inventory`，
                // 类型对不上，所以这里用具体类型判断而不是"有没有 inventory 属性"。
                val virtual = slot as? VirtualInventoryLogisticSlot ?: continue
                if (virtual.slot < 0) continue
                result += VirtualCargoSlot(virtual.inventory, virtual.slot)
            }
            return result.ifEmpty { null }
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

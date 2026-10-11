package top.mc506lw.rebar.pipez

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.PipeType
import top.mc506lw.rebar.pipez.items.ConduitItem

/**
 * 方块注册。
 *
 * **管段的底块是不可见的 `STRUCTURE_VOID`** —— 和 Pylon 的货运管道一样。
 * 原因：Rebar 的方块外观走 `BlockTextureEntity`（packet 级展示实体），
 * 如果底块是实体方块（比如混凝土），你看到的永远是那个方块，展示实体被它盖住。
 *
 * ⚠️ **物品材质必须和方块材质完全一致**，否则放置时 Rebar 直接抛：
 * ```
 * IllegalStateException: Item ... places block ... and so must have the same type
 * ```
 * 所以物品也是 `STRUCTURE_VOID`，靠 `ITEM_MODEL` 数据组件让它在背包里显示成对应颜色的混凝土
 * （这是 Pylon 的标准做法）。想换背包外观就改这个 `ITEM_MODEL`。
 *
 * ## 每种管道类型一个方块 + 一个物品
 *
 * 能量管、流体管是**两个独立内容**（不是同一根管换色）：
 * 各有一组方块键 / 物品键，背包里也是两样东西，铺在一起也不会互连。
 */
object PipezBlocks {

    /**
     * 某种类型管道的物品。
     *
     * 材质必须 = 方块材质（`STRUCTURE_VOID`）；背包外观用 `ITEM_MODEL` 指成
     * 对应颜色的混凝土，**和它的彩条颜色一致**，一眼能认出是哪种管。
     */
    fun conduitItemFor(type: PipeType): ItemStack =
        ItemStackBuilder.rebar(Material.STRUCTURE_VOID, PipezKeys.pipe(type))
            .set(DataComponentTypes.ITEM_MODEL, itemModelFor(type).key)
            .build()

    /**
     * 背包外观：能量管用红色混凝土、流体管用浅蓝混凝土、物品管用橙色混凝土
     * —— 和彩条同色系，但因为底块是黑的，这里用深一点的颜色更耐看。
     */
    private fun itemModelFor(type: PipeType): Material = when (type) {
        PipeType.ENERGY -> Material.RED_CONCRETE
        PipeType.FLUID -> Material.LIGHT_BLUE_CONCRETE
        PipeType.ITEM -> Material.ORANGE_CONCRETE
    }

    fun initialize() {
        // 每种类型的管道：注册方块 + 对应的放置物品（键必须相同玩家才能放）
        for (type in PipeType.entries) {
            val key = PipezKeys.pipe(type)
            RebarBlock.register(key, Material.STRUCTURE_VOID, ConduitBlock::class.java)
            RebarItem.register(ConduitItem::class.java, conduitItemFor(type), key)
        }

        // 旧版"全能管"：保留注册，避免老存档里的管子失效。
        // 加载后 ConduitBlock 会把它迁移成能量管（见 ConduitBlock.pipeType 的推导）。
        RebarBlock.register(PipezKeys.CONDUIT, Material.STRUCTURE_VOID, ConduitBlock::class.java)
        RebarItem.register(ConduitItem::class.java, legacyConduitItem(), PipezKeys.CONDUIT)
    }

    /** 旧版管子的物品（只在读取老存档时用得上，保持和 0.1.x 一致）。 */
    private fun legacyConduitItem(): ItemStack =
        ItemStackBuilder.rebar(Material.STRUCTURE_VOID, PipezKeys.CONDUIT)
            .set(DataComponentTypes.ITEM_MODEL, Material.GRAY_CONCRETE.key)
            .build()
}

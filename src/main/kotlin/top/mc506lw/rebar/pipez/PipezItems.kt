package top.mc506lw.rebar.pipez

import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.conduit.ConduitTier
import top.mc506lw.rebar.pipez.items.UpgradeItem
import top.mc506lw.rebar.pipez.items.WrenchItem

/** 物品注册。 */
object PipezItems {

    /** 扳手：右键管段切换某个方向的连接。 */
    val WRENCH: ItemStack = ItemStackBuilder.rebar(Material.CLAY_BALL, PipezKeys.WRENCH).build()

    /**
     * 升级模块：每个档位一个，右键管段插进那个面的升级槽。
     *
     * ⚠️ **最大堆叠 = 1**：升级是"每个面装一个"，能堆叠的话玩家会往槽里塞一摞，
     * 界面和语义都会乱（用户明确要求"升级只能安装一个，不能堆叠多个"）。
     */
    val UPGRADES: Map<ConduitTier, ItemStack> = ConduitTier.entries.associateWith { tier ->
        ItemStackBuilder.rebar(Material.CLAY_BALL, PipezKeys.upgrade(tier))
            .editMeta { meta -> meta.setMaxStackSize(1) }
            .build()
    }

    fun initialize() {
        RebarItem.register(WrenchItem::class.java, WRENCH, PipezKeys.WRENCH)
        UPGRADES.forEach { (tier, stack) ->
            RebarItem.register(UpgradeItem::class.java, stack, PipezKeys.upgrade(tier))
        }
    }
}

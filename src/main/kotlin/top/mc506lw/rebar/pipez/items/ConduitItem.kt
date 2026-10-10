package top.mc506lw.rebar.pipez.items

import io.github.pylonmc.rebar.i18n.RebarArgument
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.util.gui.unit.UnitFormat
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.conduit.ConduitTier
import top.mc506lw.rebar.pipez.conduit.PipeType

/**
 * 管道的放置物品。方块本身是 [top.mc506lw.rebar.pipez.conduit.ConduitBlock]，
 * 物品只是它的"壳"——键必须和方块相同，玩家才能放下来。
 *
 * ## 每种管道类型一个物品
 *
 * 能量管和流体管是两样独立的东西（键 `pipe_energy` / `pipe_fluid`）。
 * 物品的名字 / lore 走 lang 里各自的条目（`item.pipe_energy.*` / `item.pipe_fluid.*`），
 * 这里把它自己是什么类型、能跑多少，作为占位符交给 lang。
 *
 * 管段没有档位之分（档位是"装上去"的，见 [UpgradeItem]），所以物品只有一种外观。
 */
class ConduitItem(stack: ItemStack) : RebarItem(stack) {

    /** 本物品对应的管道类型（由键反解；旧键 `conduit` 归为能量管）。 */
    private val type: PipeType = PipeType.fromItemKey(getKey().key) ?: PipeType.ENERGY

    override fun getPlaceholders(): List<RebarArgument> = when (type.category) {
        // 能量管：显示基础档的瓦特上限（升级后会更高）
        PipeType.Category.ENERGY -> listOf(
            RebarArgument.of("max-power", UnitFormat.WATTS.format(PipezConfig.capacity(ConduitTier.BASIC)))
        )
        // 流体管：显示统一速率（mB/s），流体侧没有档位
        PipeType.Category.FLUID -> listOf(
            RebarArgument.of("fluid-rate", PipezConfig.fluidPerSecond.toLong().toString())
        )
        // 物品管还没做（只有类别，没有条目），暂时不会有物品走到这里
        PipeType.Category.ITEM -> emptyList()
    }
}

package top.mc506lw.rebar.pipez.items

import io.github.pylonmc.rebar.event.api.annotation.MultiHandler
import io.github.pylonmc.rebar.i18n.RebarArgument
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.interfaces.InteractRebarItemHandler
import io.github.pylonmc.rebar.util.gui.unit.UnitFormat
import org.bukkit.GameMode
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.conduit.CARDINAL_FACES
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.ConduitTier
import top.mc506lw.rebar.pipez.conduit.FaceMode
import top.mc506lw.rebar.pipez.conduit.rebarAt

/**
 * 升级模块：**插进**管段某个面的槽里（不是被吃掉）。
 *
 * 抄 Pipez 的取舍 —— 升级**存在管子上的槽里**，玩家随时能拿回去，
 * 所以只需要"管段 + 模块"两样东西，不需要给每一档做一种管段。
 *
 * ## 两种插入方式（行为一致）
 *
 * 1. **右键管段的某个面** → 模块进那个面的槽（本类的 [onInteract]）。
 * 2. 打开配置界面 → 直接把模块拖进升级槽（见 `ConduitGui`）。
 *
 * 两种方式都是**把物品放进槽**，槽里原有的模块会**还给玩家**（不是销毁）。
 * 拔掉模块 = 降回基础档 —— 这是 Pipez 的行为，玩家能预期。
 *
 * ## 哪些管有槽
 *
 * - **能量管**：有 —— 档位决定瓦特上限。
 * - **物品管**：有 —— 档位是界面功能的钥匙（红石 / 分配 / 过滤）。
 * - **流体管**：**没有**（对齐 Pipez 的 `FluidPipeType.hasUpgrade() == false`）。
 *
 * ## 插不进去的情况
 *
 * - **流体管**：没有升级槽，模块原样留在手里。
 * - **非抽取面**：升级只挂在"抽取端"上（和配置界面的打开条件一致）。
 * - **档位没变高**：允许放同级或更低档（只是没提升）——
 *   反正模块是"存"在槽里，不是"消费"。
 */
class UpgradeItem(stack: ItemStack) : RebarItem(stack), InteractRebarItemHandler {

    private val tier: ConduitTier =
        ConduitTier.fromItemKey(getKey().key) ?: ConduitTier.BASIC

    override fun getPlaceholders(): List<RebarArgument> = listOf(
        RebarArgument.of("tier", tier.id),
        RebarArgument.of("max-power", UnitFormat.WATTS.format(PipezConfig.capacity(tier)))
    )

    @MultiHandler(priorities = [EventPriority.HIGH])
    override fun onInteract(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val clicked = event.clickedBlock ?: return
        val conduit = rebarAt(clicked) as? ConduitBlock ?: return

        // 流体管没有升级槽（对齐 Pipez 的 FluidPipeType.hasUpgrade() == false）
        if (!conduit.acceptsUpgrade()) return

        val face = event.blockFace
        if (face !in CARDINAL_FACES) return

        // ⚠️ **只能插在"抽取"那一面** —— 管道的性能是由抽取端口决定的，
        // 非抽取面没有"性能"可言（和配置界面的打开条件保持一致）。
        if (conduit.faceMode(face) != FaceMode.EXTRACT) {
            event.player.sendActionBar(
                net.kyori.adventure.text.Component.text(
                    "升级模块只能插在「抽取」端（潜行 + 扳手右键切换模式）",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY
                )
            )
            return
        }

        event.isCancelled = true

        val player = event.player
        // 手上这一个模块进槽；槽里原有的模块还给玩家。
        val incoming = player.inventory.itemInMainHand.clone().apply { amount = 1 }
        val replaced = conduit.setSideUpgrade(face, incoming)

        // 手里扣掉一个（创造模式不扣）
        if (player.gameMode != GameMode.CREATIVE) {
            val hand = player.inventory.itemInMainHand.clone()
            hand.amount -= 1
            player.inventory.setItemInMainHand(if (hand.amount <= 0) null else hand)
        }

        // 把换下来的旧模块还回去（塞不下就掉在脚下）
        replaced?.let {
            val overflow = player.inventory.addItem(it)
            overflow.values.forEach { extra -> player.world.dropItem(player.location, extra) }
        }
    }
}

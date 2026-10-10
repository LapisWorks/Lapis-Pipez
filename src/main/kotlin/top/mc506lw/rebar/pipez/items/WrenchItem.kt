package top.mc506lw.rebar.pipez.items

import io.github.pylonmc.rebar.event.api.annotation.MultiHandler
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.interfaces.InteractRebarItemHandler
import io.github.pylonmc.rebar.util.gui.unit.UnitFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.BlockFace
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
 * 管道扳手 —— 复刻 Pipez 的 `WrenchItem` + `PipeBlock.onWrenchClicked`。
 *
 * ## 用法（潜行 + 右键）
 *
 * - 那一格是**同类型管** → 在 `断 / 连` 之间切换；
 * - 那一格是**末端**（空气 / 机器 / 异色管）→ 在 `普通 / 抽取 / 断路` 之间循环。
 *
 * 具体循环规则见 [ConduitBlock.wrenchCycle]。
 *
 * ## 为什么必须潜行
 *
 * 不潜行右键是"打开配置界面"的手势（`GuiRebarBlock` 会自动接管），两者会打架。
 * Pipez 的 `onWrenchClicked` 第一行就是 `if (!player.isShiftKeyDown()) return PASS;`，
 * 这里保持一致。
 *
 * 用 `@MultiHandler(priorities = [HIGH])` 保证**只收到一次**回调 ——
 * 否则同一个右键事件被投递多次，就会把模式来回切好几遍。
 */
class WrenchItem(stack: ItemStack) : RebarItem(stack), InteractRebarItemHandler {

    @MultiHandler(priorities = [EventPriority.HIGH])
    override fun onInteract(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val clicked = event.clickedBlock ?: return
        val conduit = rebarAt(clicked) as? ConduitBlock ?: return

        val face: BlockFace = event.blockFace
        if (face !in CARDINAL_FACES) return

        // 不潜行 → 让给"打开界面"那条路径（这里什么都不做）
        if (!event.player.isSneaking) return

        event.isCancelled = true
        val mode = conduit.wrenchCycle(face)

        // 即时反馈 —— 之前扳手"切了但看不出来"，玩家不知道到底生效没有。
        // 顺手把**这一面的档位/上限**也报出来，方便确认升级模块到底有没有被读进去。
        //
        // ⚠️ 这里必须**拼 Component**，不能拼 StringBuilder：
        // `UnitFormat.WATTS.format(...)` 返回的是 `UnitFormat.Formatted`（一个 `ComponentLike`），
        // 直接丢进字符串模板 / `StringBuilder.append(Object)` 会走到它的 `toString()`，
        // 打出来就是 `io.github.pylonmc.rebar.util.gui.unit.UnitFormat$Formatted@52be6103`。
        // 正确做法是 `.build()` 成 `Component` 再 append。
        var msg: Component = Component.text("${faceName(face)} · ${modeName(mode)}", NamedTextColor.YELLOW)
        if (conduit.acceptsUpgrade()) {
            val tier = conduit.tierOf(face)
            msg = msg.append(Component.text("  |  档位 ${tierName(tier)}", NamedTextColor.YELLOW))
            // ⚠️ 档位**只在抽取面上有电学意义**（对标 Pipez：速率属于抽取面）。
            // 所以只有刚切到"抽取"时才报上限，免得在普通面上给出误导信息。
            if (mode.directional) {
                msg = if (PipezConfig.tierPowerLimit) {
                    msg.append(Component.text("（本面限流 ", NamedTextColor.YELLOW))
                        .append(UnitFormat.WATTS.format(PipezConfig.capacity(tier)).build())
                        .append(Component.text("）", NamedTextColor.YELLOW))
                } else {
                    msg.append(Component.text("（限流已在 config 关闭）", NamedTextColor.YELLOW))
                }
            }
        }
        event.player.sendActionBar(msg)
    }

    private fun faceName(face: BlockFace): String = when (face) {
        BlockFace.UP -> "上面"
        BlockFace.DOWN -> "下面"
        BlockFace.NORTH -> "北面"
        BlockFace.SOUTH -> "南面"
        BlockFace.EAST -> "东面"
        BlockFace.WEST -> "西面"
        else -> face.name
    }

    private fun modeName(mode: FaceMode): String = when (mode) {
        FaceMode.NORMAL -> "普通"
        FaceMode.EXTRACT -> "抽取"
        FaceMode.NONE -> "断路"
    }

    private fun tierName(tier: ConduitTier): String = when (tier) {
        ConduitTier.BASIC -> "基础"
        ConduitTier.IMPROVED -> "强化"
        ConduitTier.ADVANCED -> "高级"
        ConduitTier.ULTIMATE -> "究极"
    }
}

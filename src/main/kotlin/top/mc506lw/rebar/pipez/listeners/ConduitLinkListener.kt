package top.mc506lw.rebar.pipez.listeners

import io.github.pylonmc.rebar.block.interfaces.ElectricRebarBlock
import io.github.pylonmc.rebar.event.RebarBlockBreakEvent
import io.github.pylonmc.rebar.event.RebarBlockLoadEvent
import io.github.pylonmc.rebar.event.RebarBlockPlaceEvent
import io.github.pylonmc.rebar.event.RebarBlockUnloadEvent
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import top.mc506lw.rebar.pipez.cargo.CargoEndpoint
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.ConduitLinker
import top.mc506lw.rebar.pipez.conduit.ConduitNetwork
import top.mc506lw.rebar.pipez.fluid.ConduitFluid

/**
 * 邻接变化接入点。
 *
 * 只做一件事：把受影响的管段塞进 [ConduitNetwork] 的 dirty 队列，**不在这里重算**。
 * 真正的重算在 tick 末由 `PipezAddon` 调 `ConduitNetwork.flush()` 统一做。
 *
 * 覆盖范围：
 * - 管段被放置 / 拆掉
 * - 机器（任何 `RebarBlock`）被放置 / 拆掉 —— 因为管段可能要和它连
 * - 任何 `RebarBlock` 被加载 —— 机器所在区块比管道晚加载时，管道那边要重算
 * - **原版容器**被放置 / 拆掉 —— 物品管要靠它认端点（Rebar 事件覆盖不到原版方块）
 *
 * 前三个用 Rebar 的事件（只有 Rebar 方块才有节点），最后一个必须用原版事件 ——
 * 但**只对容器**（箱子 / 桶 / 漏斗…）才 mark，普通圆石放上去照样不影响连接。
 */
object ConduitLinkListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRebarBlockPlaced(event: RebarBlockPlaceEvent) {
        ConduitNetwork.markAround(event.block.position)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRebarBlockBroken(event: RebarBlockBreakEvent) {
        // 机器被拆 → 它的端口缓存失效，否则下次同一格放另一台机器会拿到旧缓存
        (event.rebarBlock as? ElectricRebarBlock)?.let(ConduitLinker::invalidate)
        ConduitNetwork.markAround(event.block.position)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRebarBlockUnloaded(event: RebarBlockUnloadEvent) {
        (event.rebarBlock as? ConduitBlock)?.let(ConduitFluid::detach)
        // 方块卸载 → 端口实体也可能被卸载，缓存里的坐标就不可信了
        (event.rebarBlock as? ElectricRebarBlock)?.let(ConduitLinker::invalidate)
    }

    /**
     * 机器所在的区块比管道晚加载时，管道那边的连接要重新算一次
     * （否则"先放管道、后加载机器"的存档会一直连不上）。
     *
     * ⚠️ 同时要让端口缓存失效：机器是**重新加载**进来的，虽然坐标一样，
     * 但端口实体是全新的，缓存里存的旧 UUID 可能已经不通用了。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRebarBlockLoaded(event: RebarBlockLoadEvent) {
        (event.rebarBlock as? ElectricRebarBlock)?.let(ConduitLinker::invalidate)
        ConduitNetwork.markAround(event.rebarBlock.block.position)
    }

    /**
     * **原版容器**被放置 —— 物品管要认它是端点。
     *
     * 管道不会因为一个箱子放在它旁边就自动开始搬（那是"抽取面"的职责），
     * 但**连接形状**要重算：物品管的某面从一开始"贴着空气"变成"贴着容器"，
     * 得让它知道该画那条臂了。
     *
     * ⚠️ 只对**物品管**可能有用；但这里没法便宜地判断相邻是不是物品管，
     * 所以直接 markAround —— 批处理会去重，代价可控。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVanillaBlockPlaced(event: BlockPlaceEvent) {
        if (!CargoEndpoint.isContainer(event.block)) return
        ConduitNetwork.markAround(event.block.position)
    }

    /**
     * **原版容器**被拆 —— 同理要重算（那条臂该消失了）。
     *
     * ⚠️ 必须在方块**真的变成空气之前**判断它是不是容器：MONITOR 阶段拿到的是
     * 破坏前的 `Block`，`InventoryHolder` 还在，能正确判断。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVanillaBlockBroken(event: BlockBreakEvent) {
        if (!CargoEndpoint.isContainer(event.block)) return
        ConduitNetwork.markAround(event.block.position)
    }
}

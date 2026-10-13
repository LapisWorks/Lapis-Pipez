package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.BlockFace

/**
 * 批处理 / 去抖队列。
 *
 * 所有"邻居变化"（放置、破坏、扳手、区块加载）**只登记，不立即重算**；
 * 服务器每个 tick 统一 [flush] 一次。
 *
 * 为什么必须这样：
 * Rebar 的 `ElectricNode.connect()/disconnectFrom()` 每次都会触发
 * `markDirty()` + `mergeNetworks()`/`refreshNetwork()`（O(网络规模)）。
 * 如果在事件回调里逐个连接，一次批量放置会触发成百上千次网络合并。
 * 批处理保证**一个 tick 内无论改了多少管段，重算次数只与"受影响的连通块"有关**，
 * 而不是与"改动的管段数"成正比。
 *
 * 注意：批处理只能合并"我们主动发起的重算"，无法消除 Rebar 自己在 `connect()` 里的合并。
 * 因此 [ConduitBlock.refresh] 必须**幂等**（求差集再增删），避免"先全断再全连"制造额外合并。
 */
object ConduitNetwork {

    private val dirty = LinkedHashSet<BlockPosition>()

    /** 登记某个位置**及其六个邻居**——邻居的连通关系可能因它而变化。 */
    fun markAround(pos: BlockPosition) {
        // 拓扑一变，"一台机器该由哪个端口出面"就可能变 → 清掉防短接缓存。
        // 缓存的键是 (管格, 面)，一次 markAround 会连带清掉无关项，但表很小、
        // 而且只在有机器贴着的管格上才会被填，代价可以接受。
        MachinePortGuard.invalidate()
        dirty += pos
        for (face in CARDINAL_FACES) dirty += pos.getRelative(face)
    }

    /** 只登记一个位置。 */
    fun mark(pos: BlockPosition) {
        dirty += pos
    }

    /**
     * 处理本 tick 累积的全部变更。
     *
     * ## 两轮：先把"run 起点"补进来，再统一刷新
     *
     * [ConduitBlock.spawnRun] 是**贪婪合并**的：一整条直线段只由**起点格**负责画。
     * 这带来一个不显眼但致命的后果 ——
     * 在一条长直管的**末端**接上一格新管子时，受影响（被 mark）的只有末端附近，
     * **几格之外的起点格根本不知道自己该把管子画长一点**。
     * 于是新接的那一格什么都不画，视觉上就是"连着连着就断了"。
     *
     * 所以这里在刷新前，对每个脏格再补一个"它所在 run 的起点"进 batch
     * —— 起点被刷新了，它才会重新沿直线走到底、把管子延到新末端。
     *
     * ⚠️ 必须**先收集完 batch 再统一刷新**：如果在刷新过程中往 batch 里加，
     * 当前这次循环会看到新增项（`toList()` 已经在刷新前拍过照了，所以其实安全，
     * 但显式分两轮更好读，也避免"刷新时又改 dirty"的歧义）。
     */
    fun flush() {
        if (dirty.isEmpty()) return
        val batch = LinkedHashSet(dirty)
        dirty.clear()

        // 第一轮：把每个脏格**影响到**的那些 run 的起点也拉进来。
        // 记下这些起点 —— 第二轮要**强制**它们重建显示（见下）。
        val runStarts = HashSet<BlockPosition>()
        for (pos in batch.toList()) {
            val conduit = rebarAt(pos) as? ConduitBlock ?: continue
            // ① 本格自己所在的 run
            runStartOf(conduit)?.let {
                runStarts += it
                batch += it
            }
            // ② ⚠️ **穿过拐角 / 三通**的那些 run —— 这是"放置顺序不同 → 渲染不同"的另一半。
            //
            // [runStartOf] 第一句就是 `runAxis() ?: return null`，而拐角 / 三通的
            // `runAxis()` 恰好是 null → **以它们为端点的那些 run 的起点永远进不了批次**。
            // 于是那条 run 的起点不会被强制重建：它的几何（run 长度 / 末端收口）明明
            // 该变，却既不重建也不清除 → **旧的显示实体留在世界上**。
            // 用户实测：先放竖管、再在旁边接一格横管，拐角下方会残留一截旧管口。
            //
            // 只在"拐角 / 三通 / 十字"时才做这个额外遍历（见 [ConduitShape.crossRunDirections]）：
            // 直管格（含度数 1 的端点）的同类邻居必定在同一条轴上，① 已经覆盖，
            // 没必要再走一遍（每次都要沿轴走到底，代价 O(run 长度)）。
            for (dir in ConduitShape.crossRunDirections(conduit.shapeMask())) {
                val face = dir.toBlockFace()
                if (!conduit.isConnectedOn(face)) continue
                val next = rebarAt(pos.getRelative(face)) as? ConduitBlock ?: continue
                // 异色管不在同一条 run 里（类型都不同），跳过
                if (next.pipeType != conduit.pipeType) continue
                runStartOf(next)?.let {
                    runStarts += it
                    batch += it
                }
            }
        }

        // 第二轮：统一刷新
        for (pos in batch) {
            // 位置可能已经被拆掉、或者所在区块已经卸载 —— 两种都要静默跳过。
            // 注意必须用 rebarAt 而不是 BlockStorage.get：后者在未加载区块上会抛异常。
            val conduit = rebarAt(pos) as? ConduitBlock ?: continue
            // ⚠️ run 起点必须**强制**重建显示，不能走"掩码没变就跳过"的去重。
            //
            // 因为整条 run 由起点格画，起点的渲染取决于**run 长度**；
            // 而"在远端加一格"时起点自己的形状掩码**完全没变** →
            // 去重逻辑会跳过它 → 新加的那一格什么都不画（用户："放了能量管道但没渲染"）。
            conduit.refresh(forceDisplays = pos in runStarts)
        }
    }

    /**
     * [conduit] 所在直线段的**起点格**位置；不是直线段返回 null。
     *
     * 沿轴的反方向一路走，走到"再往前就不是同一轴的连通管段"为止。
     * 用 [ConduitBlock.runAxis] 保证和渲染侧判据完全一致。
     */
    private fun runStartOf(conduit: ConduitBlock): BlockPosition? {
        val axis = conduit.runAxis() ?: return null
        val back = when (axis) {
            Axis.X -> BlockFace.WEST
            Axis.Y -> BlockFace.DOWN
            Axis.Z -> BlockFace.NORTH
        }
        var pos = conduit.block.position
        // 本格朝反方向**没连着**（断路 / 对面断路）→ 本格自己就是起点。
        // 和 [ConduitBlock.isRunStart] 的判据保持一致，否则断路之后那一段会
        // 认错起点、刷新不到正确的格子。
        if (!conduit.isConnectedOn(back)) return pos
        var guard = 0
        while (guard++ < MAX_RUN_SCAN) {
            val prev = rebarAt(pos.getRelative(back)) as? ConduitBlock ?: break
            if (!prev.isConnectedOn(back.oppositeFace)) break
            if (prev.runAxis() != axis) break
            pos = prev.block.position
        }
        return pos
    }

    fun clear() = dirty.clear()

    /** 待处理数量，供调试 / 单测观察。 */
    val pending: Int get() = dirty.size

    /** 找 run 起点时沿直线回退的最大步数，和渲染侧的 `MAX_RUN` 保持一致。 */
    private const val MAX_RUN_SCAN = 512
}

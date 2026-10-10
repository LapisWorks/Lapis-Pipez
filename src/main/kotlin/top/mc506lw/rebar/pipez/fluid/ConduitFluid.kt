package top.mc506lw.rebar.pipez.fluid

import io.github.pylonmc.rebar.block.interfaces.FluidRebarBlock
import io.github.pylonmc.rebar.fluid.FluidPointType
import io.github.pylonmc.rebar.fluid.VirtualFluidPoint
import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.bridge.FluidBridge
import top.mc506lw.rebar.pipez.conduit.CARDINAL_FACES
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.FaceMode
import top.mc506lw.rebar.pipez.conduit.PipeType
import top.mc506lw.rebar.pipez.conduit.rebarAt

/**
 * 管段的流体网络（设计文档的**决策 C**）。
 *
 * ## 只在"端点与交汇处"建点
 *
 * `FluidManager.tick(segment)` 每个 tick 会遍历该 segment 的**所有点**。
 * 如果每个管段都建一个 `VirtualFluidPoint`，一万管段就是每 tick 扫一万个点。
 *
 * 所以规则是：**只有当管段"度 ≠ 2"（端点 / 交汇）或贴着机器的流体口时，才建点。**
 * 一段 16 格的长直管 = 2 个点，中间 14 格一个点都不建；
 * 两点之间靠 [link] 里"沿直线找下一个点"连成一条边，tick 成本与管长无关。
 *
 * ## 为什么走 [FluidBridge]
 *
 * `FluidManager` 是 Kotlin `internal`，公开 API 建不了连接。详见 [FluidBridge] 的说明。
 * 这个对象只在 [FluidBridge.available] 为 true 时才会被调用。
 *
 * ## 生命周期
 *
 * 点**不落盘**：区块加载时所有管段都会 `refresh` 一遍，网络整体重建，天然一致。
 * 运行期的点存在 [points] 这张内存表里；方块被拆 / 区块卸载时移除。
 */
object ConduitFluid {

    /** 沿直线找下一个点的最大步数，防止配置错误时死循环。 */
    private const val MAX_RUN = 512

    private val points = HashMap<BlockPosition, VirtualFluidPoint>()

    /** 由 [ConduitBlock.refresh] 调用。 */
    fun refresh(conduit: ConduitBlock) {
        if (!FluidBridge.available) return

        val needed = needsPoint(conduit)
        val existing = points[conduit.block.position]
        val wantedType = pointTypeFor(conduit)

        val point = when {
            needed && existing == null -> createPoint(conduit)

            // 需要点、但**类型变了**（玩家在界面里改了某个面的模式）→ 重建。
            // `VirtualFluidPoint.type` 是 final，改不了，只能拆了重来。
            // 类型决定"供给方 / 需求方"，不重建方向就不生效。
            needed && existing != null && existing.type != wantedType -> {
                destroyPoint(conduit)
                createPoint(conduit)
            }

            !needed && existing != null -> {
                destroyPoint(conduit)
                null
            }

            else -> existing
        }

        if (point != null) link(conduit, point)
    }

    /** 方块被拆 / 区块卸载时调用。 */
    fun detach(conduit: ConduitBlock) {
        if (points.containsKey(conduit.block.position)) destroyPoint(conduit)
    }

    fun clear() {
        points.values.forEach(FluidBridge::remove)
        points.clear()
    }

    val activePoints: Int get() = points.size

    // ---------------------------------------------------------------- 判定

    /**
     * 需不需要建点：**度 ≠ 2**（端点或交汇）**或**贴着机器的流体口。
     *
     * 度按"相邻**流体**管段数"算 —— 这里关心的是流体拓扑，电力管和电力连接都不算。
     *
     * ⚠️ 必须只数流体管：能量管也是 `ConduitBlock`，如果按类型不管，
     * 一根贴着流体管铺的能量管会被误判成"度 ≠ 2"而在流体网络里凭空建点。
     */
    private fun needsPoint(conduit: ConduitBlock): Boolean {
        val conduits = CARDINAL_FACES.count {
            isFluidConduit(rebarAt(conduit.block.getRelative(it)))
        }
        if (conduits != 2) return true
        return CARDINAL_FACES.any { machinePointAt(conduit.block, it) != null }
    }

    /** [rebar] 是不是一根流体管。 */
    private fun isFluidConduit(rebar: Any?): Boolean =
        rebar is ConduitBlock && rebar.pipeType.category == PipeType.Category.FLUID

    /**
     * [machine] 在 [face] 那一面**有没有**流体口。供 [ConduitBlock] 判断"该不该连"。
     *
     * 和电力侧一个道理："这台机器是 `FluidRebarBlock`" 不等于"这一面有口"。
     * 流体口只开在特定面，所以必须精确到面 —— 否则会画出一条连不上的胳膊。
     */
    fun hasPointFacing(machine: FluidRebarBlock, face: org.bukkit.block.BlockFace): Boolean =
        pointFacing(machine, face) != null

    /**
     * [machine] 在 [face] 那一面上的流体点；没有返回 null。
     *
     * `getFluidPointDisplay(type, face)` 本身就是**按面查**的公开 API，
     * 所以这里不需要像电力侧那样反推实体坐标 —— 直接问就行。
     */
    private fun pointFacing(
        machine: FluidRebarBlock,
        face: org.bukkit.block.BlockFace
    ): VirtualFluidPoint? {
        for (type in FluidPointType.entries) {
            val display = runCatching { machine.getFluidPointDisplay(type, face) }.getOrNull()
            if (display != null) return display.point
        }
        return null
    }

    // ---------------------------------------------------------------- 建 / 拆

    /**
     * 建一个管段的流体点。
     *
     * ## 点类型由"这个面干什么"决定
     *
     * `FluidManager` 的语义（已 javap 确认）：
     * - [FluidPointType.OUTPUT] 的点**向外供液**（进"来源"表）；
     * - [FluidPointType.INPUT] 的点**索取液体**（进"需求"表）；
     * - [FluidPointType.INTERSECTION] 纯交叉，既不来也不去。
     *
     * 一根管子通常有多个面，而一个管段**只有一个**点 —— 所以我们取
     * **最"主动"的那一面**：
     * - 只要有面是 [FaceMode.EXTRACT]（从邻居抽）→ 这个点要能**接液**，
     *   用 [FluidPointType.INPUT]（在管段一级它是"需求方"）。
     * - 否则只要有面是 [FaceMode.INSERT]（往邻居送）→ 用 [FluidPointType.OUTPUT]。
     * - 都没有（孤立 / 全 NONE）→ [FluidPointType.INTERSECTION]。
     *
     * ⚠️ 这是**管段级**的近似：真正的方向语义由"机器那一侧的端口类型"承担
     * （机器的口是 INPUT 还是 OUTPUT 不归我们管）。这里决定的是**管段自己**
     * 在流体网络里扮演供给方还是需求方。
     */
    private fun createPoint(conduit: ConduitBlock): VirtualFluidPoint? = runCatching {
        val point = VirtualFluidPoint(conduit.block, pointTypeFor(conduit))
        FluidBridge.add(point)
        points[conduit.block.position] = point
        point
    }.getOrNull()

    /** 由六面模式推导管段点的类型，见 [createPoint] 的说明。 */
    private fun pointTypeFor(conduit: ConduitBlock): FluidPointType {
        val extracting = CARDINAL_FACES.any { conduit.faceMode(it) == FaceMode.EXTRACT }
        return if (extracting) FluidPointType.INPUT else FluidPointType.INTERSECTION
    }

    private fun destroyPoint(conduit: ConduitBlock) {
        val point = points.remove(conduit.block.position) ?: return
        FluidBridge.remove(point)
    }

    // ---------------------------------------------------------------- 连线

    /**
     * 幂等地把本管段的点连到"应该连的"点上。
     *
     * 六个方向各自找"下一个点"：
     * - 邻居是带点的管段 → 连它
     * - 邻居是机器且那一面有流体口 → 连机器的点
     * - 邻居是不带点的管段 → 沿直线一路走下去，直到遇到带点的管段或机器
     *
     * 和电力侧一样**先求差集再增删**，不要"先全断再全连"。
     *
     * ## ⚠️ "连着连着就断"的同一类坑
     *
     * [nextPointIn] 在**邻居的点还没建出来**时会返回 null（区块刚加载、刷新顺序靠后）。
     * 如果直接按"null 就是该断"处理，一条合法边会被断掉，而且没有任何事件会把它连回来。
     *
     * 所以这里多一层 [pendingTargets]：把"那一侧是流体管 / 有流体口的机器、
     * 但点暂时取不到"的现有连接保护起来，不参与差集里的"断"。
     */
    private fun link(conduit: ConduitBlock, point: VirtualFluidPoint) {
        val targets = LinkedHashSet<VirtualFluidPoint>()
        val pending = HashSet<VirtualFluidPoint>()

        for (face in CARDINAL_FACES) {
            val next = nextPointIn(conduit.block, face)
            if (next != null) {
                if (next.id != point.id) targets += next
                continue
            }
            // 取不到下一个点：看看那一侧是不是"本该连上"的东西
            existingTargetAt(conduit.block, face)?.let { pending += it }
        }

        val current = FluidBridge.getAllConnected(point)
        for (existing in current) {
            if (existing in targets) continue
            if (existing in pending) continue
            FluidBridge.disconnect(point, existing)
        }
        for (target in targets) {
            if (target !in current) FluidBridge.connect(point, target)
        }

        FluidBridge.setFluidPerSecond(point.segment, PipezConfig.fluidPerSecond)
    }

    /**
     * [block] 的 [face] 侧"本该连到、但当前取不到点"的那一侧，
     * 在我们**现有连接**里对应的点。
     *
     * 判据：那一侧是流体管（点可能还没建），或是有流体口的机器。
     * 返回的是"现有连接中，位置落在那一侧"的点 —— 用来保护它不被误断。
     */
    private fun existingTargetAt(block: Block, face: BlockFace): VirtualFluidPoint? {
        val neighborBlock = block.getRelative(face)
        val neighbor = rebarAt(neighborBlock) ?: return null
        val plausible = when {
            neighbor is ConduitBlock -> neighbor.pipeType.category == PipeType.Category.FLUID
            neighbor is FluidRebarBlock -> true
            else -> false
        }
        if (!plausible) return null

        // 沿这个方向一路找"现有连接里落在这一侧的点"
        val dir = face
        var current = neighborBlock
        var guard = 0
        while (guard++ < MAX_RUN) {
            points[current.position]?.let { return it }
            val rebar = rebarAt(current) ?: return null
            if (rebar !is ConduitBlock || rebar.pipeType.category != PipeType.Category.FLUID) return null
            if (!isStraightThrough(rebar, dir)) return null
            current = current.getRelative(dir)
        }
        return null
    }

    /** 从 [block] 的 [face] 方向出发，找最近的"有点的地方"。 */
    private fun nextPointIn(block: Block, face: BlockFace): VirtualFluidPoint? {
        var current = block.getRelative(face)
        var direction = face
        var guard = 0

        while (guard++ < MAX_RUN) {
            when (val rebar = rebarAt(current)) {
                is ConduitBlock -> {
                    // 只沿**流体管**走；能量管在这一侧就是死路
                    if (rebar.pipeType.category != PipeType.Category.FLUID) return null
                    points[current.position]?.let { return it }
                    // 直管中段没有点：只有"沿本方向是直的"才继续走
                    if (!isStraightThrough(rebar, direction)) return null
                    current = current.getRelative(direction)
                }

                is FluidRebarBlock -> return machinePointAt(current, direction.oppositeFace)

                else -> return null
            }
        }
        return null
    }

    /** 该流体管在 [direction] 方向上是"直通"的（前后各一个流体管邻居）。 */
    private fun isStraightThrough(conduit: ConduitBlock, direction: BlockFace): Boolean {
        val count = CARDINAL_FACES.count {
            isFluidConduit(rebarAt(conduit.block.getRelative(it)))
        }
        if (count != 2) return false
        return isFluidConduit(rebarAt(conduit.block.getRelative(direction))) &&
            isFluidConduit(rebarAt(conduit.block.getRelative(direction.oppositeFace)))
    }

    /** [block] 在 [face] 那一面上的流体口（机器侧）。 */
    private fun machinePointAt(block: Block, face: BlockFace): VirtualFluidPoint? {
        val neighbor = rebarAt(block.getRelative(face)) as? FluidRebarBlock ?: return null
        return pointFacing(neighbor, face.oppositeFace)
    }
}

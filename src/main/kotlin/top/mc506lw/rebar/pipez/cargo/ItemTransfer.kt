package top.mc506lw.rebar.pipez.cargo

import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.conduit.CARDINAL_FACES
import top.mc506lw.rebar.pipez.conduit.ConduitBlock
import top.mc506lw.rebar.pipez.conduit.ConduitGraph
import top.mc506lw.rebar.pipez.conduit.Distribution
import top.mc506lw.rebar.pipez.conduit.FaceMode
import top.mc506lw.rebar.pipez.conduit.FilterMode
import top.mc506lw.rebar.pipez.conduit.PipeType
import top.mc506lw.rebar.pipez.conduit.rebarAt

/**
 * 物品搬运的**中央任务**。
 *
 * ## 为什么是"中央"而不是"端点自己 tick"
 *
 * 能量 / 流体各有一套框架自带的网络（`ElectricNetwork` / `FluidManager`）在跑，
 * 物品没有 —— 只能自己搬。但**管段不 tick**是这套设计的底线（一万根管子
 * 每根每 tick 跑一次 BFS 直接崩），所以搬运**集中在少数"抽取端点"上**：
 *
 * - 一根管只有**某个面被设成 [FaceMode.EXTRACT] 且贴着容器**时，
 *   才登记成一个"抽取端点"（见 [refresh]）；
 * - 每个 tick 只遍历这些端点，每个端点顺物品管网 BFS 找一个接收容器，搬一次。
 *
 * 绝大多数管子既不抽取也不贴着容器 → 一次都不跑。
 *
 * ## 端点从哪来（这就是用户的"身边有容器才成端"）
 *
 * 没有专门的端点方块。[ConduitBlock] 在 [ConduitBlock.refresh] 时会调 [refresh]：
 * - 本格是物品管 → 看六个面，哪个面是 EXTRACT 且贴着容器，就登记那一面；
 * - 不是物品管 / 没有抽取面 → 从登记表里摘掉。
 *
 * 所以"放一根物品管紧贴箱子，再用扳手把那一面点成抽取" = 一个抽取端点，
 * 完全等价于 Pipez 里"箱子 + 抽取升级"。
 *
 * ## 路由
 *
 * 沿 [ConduitGraph]（只走 `Category.ITEM` 的管）做一次 BFS，收集**全部**接收容器：
 * 某根物品管的某个 [FaceMode.NORMAL] 面贴着容器，且该容器不是抽取源自己
 * （避免"抽出来又放回去"的无用搬运）。
 *
 * 要**全部**而不是第一个，是因为还得按 [Distribution] 在这些容器之间排序。
 *
 * 分配模式（[Distribution]）与过滤（[FilterMode]）在这里生效 ——
 * 这也是它们**第一次真正投入使用**（能量 / 流体侧还被框架网络占着）。
 */
object ItemTransfer {

    /**
     * 登记的抽取端点。
     *
     * 键 = 管格坐标，值 = 那一面（[BlockFace]）+ 抽取源的容器。
     * **一格最多一个抽取端点**：一根管的一个面抽一个容器就够了，
     * 多个面同时抽会让"先登记哪个"变得不确定。要抽多个容器就在别的格子上配。
     */
    private val extractors = HashMap<BlockPosition, Endpoint>()

    /** 每个端点的"轮询游标"，给 ROUND_ROBIN 用（记住上次送到哪个接收面）。 */
    private val cursors = HashMap<BlockPosition, Int>()

    private data class Endpoint(val face: BlockFace, val source: CargoEndpoint)

    /**
     * 刷新一根物品管的端点登记。
     *
     * 幂等：先摘掉本格旧登记，再按当前六个面重新登记（最多一个抽取面）。
     * 由 [ConduitBlock.refresh] 调用，也就是**只会在批处理 flush 时跑**，
     * 不会在事件回调里跑。
     */
    fun refresh(conduit: ConduitBlock) {
        val pos = conduit.block.position
        extractors.remove(pos)
        cursors.remove(pos)

        var best: Endpoint? = null
        for (face in CARDINAL_FACES) {
            // 只有"抽取面"才是源头；断路 / 普通面都不抽
            if (conduit.faceMode(face) != FaceMode.EXTRACT) continue
            val neighbor = conduit.block.getRelative(face)
            val source = CargoEndpoint.of(neighbor) ?: continue
            if (source.size <= 0) continue
            best = Endpoint(face, source)
            break
        }
        if (best != null) extractors[pos] = best
    }

    /** 摘掉某根管的端点登记（方块被拆时调用）。 */
    fun detach(conduit: ConduitBlock) {
        val pos = conduit.block.position
        extractors.remove(pos)
        cursors.remove(pos)
    }

    /** 清空全部登记（插件卸载时调用）。 */
    fun clear() {
        extractors.clear()
        cursors.clear()
    }

    /** 当前登记了几个抽取端点，供调试观察。 */
    val size: Int get() = extractors.size

    /**
     * 每个 tick 跑一次：让每个抽取端点各搬一次物品。
     *
     * 单项预算 = [PipezConfig.itemsPerTick]（每个端点独立），
     * 所以端点越多总吞吐越大 —— 和 Pipez 的"每个抽取升级一个速率"一致。
     */
    fun tick() {
        if (extractors.isEmpty()) return

        // 复制一份键集：tick 过程中搬运可能触发容器变化（理论上不会改登记表，
        // 但保住迭代安全，代价可忽略）。
        for (pos in extractors.keys.toList()) {
            val endpoint = extractors[pos] ?: continue
            // 管格可能已被拆 / 区块卸载 —— 用 rebarAt 做加载安全检查
            val conduit = rebarAt(pos) as? ConduitBlock ?: run {
                extractors.remove(pos)
                cursors.remove(pos)
                continue
            }
            if (conduit.pipeType.category != PipeType.Category.ITEM) {
                extractors.remove(pos)
                continue
            }
            transfer(conduit, endpoint)
        }
    }

    // ---------------------------------------------------------------- 单次搬运

    private fun transfer(conduit: ConduitBlock, endpoint: Endpoint) {
        val sourceSlots = endpoint.source.slots()
        if (sourceSlots.isEmpty()) return

        // ⚠️ BFS 每个端点每 tick **只跑一次**（不管源容器有多少槽）——
        // 最初写成"每个槽各跑一遍"，一个大箱子 27 个槽就是 27 次 BFS，
        // 端点一多直接拖垮 tick。
        val targets = resolveTargets(conduit, endpoint)
        if (targets.isEmpty()) return

        // ⚠️ 排序也**每个端点每 tick 只做一次**：ROUND_ROBIN 的游标是在
        // [orderTargets] 里前进的，如果放进下面的槽循环里，27 个槽会让游标转 27 圈，
        // "轮流分配"就退化成随机了。
        val ordered = orderTargets(conduit, targets)

        // 找出这次要搬哪些物品（受本面过滤器约束）
        val face = endpoint.face
        var budget = PipezConfig.itemsPerTick

        for (sourceSlot in sourceSlots) {
            if (budget <= 0) break
            val stack = sourceSlot.get() ?: continue
            if (stack.type == Material.AIR || stack.amount <= 0) continue
            if (!passesFilter(conduit, face, stack)) continue

            val moved = pushToTargets(stack, sourceSlot, ordered, budget)
            budget -= moved
        }
    }

    /**
     * 把 [stack] 从 [sourceSlot] 分堆送进 [targets] 里的容器，返回实际搬了几个。
     *
     * 允许**一个物品分成几堆**送进多个容器 —— 手里 64 个、每个接收容器只收得下 10 个时，
     * 会把 6 个容器各装 10 个，而不是死卡在第一个装不下的容器上。
     *
     * 接收容器的**顺序**由调用方（[transfer]）用 [orderTargets] 算好传进来 ——
     * 这样 ROUND_ROBIN 的游标每个端点每 tick 只前进一次。
     */
    private fun pushToTargets(
        stack: ItemStack,
        sourceSlot: CargoSlot,
        ordered: List<CargoEndpoint>,
        budget: Int
    ): Int {
        var leftInSource = stack.amount
        var remainingBudget = budget

        for (target in ordered) {
            if (remainingBudget <= 0 || leftInSource <= 0) break
            for (targetSlot in target.slots()) {
                if (remainingBudget <= 0 || leftInSource <= 0) break

                // 目标槽里现有多少还在源栈里 —— 用当前个数算（每搬一次源就少一点）
                val probe = stack.asQuantity(leftInSource)
                val room = targetSlot.roomFor(probe)
                if (room <= 0) continue

                val amount = minOf(remainingBudget, room, leftInSource)
                if (amount <= 0) continue

                val existing = targetSlot.get()
                val current = if (existing == null || existing.type == Material.AIR) 0 else existing.amount

                // ⚠️ 先写目标、再扣源：若目标写入被拦（VirtualInventory 的 pre-update 等），
                // 源还没动，物品不会凭空消失。反过来则可能丢物品。
                targetSlot.set(stack.asQuantity(current + amount))
                leftInSource -= amount
                remainingBudget -= amount
                sourceSlot.set(leftInSource.takeIf { it > 0 }?.let { stack.asQuantity(it) })
            }
        }
        return budget - remainingBudget
    }

    /**
     * 找接收容器：沿物品管网 BFS，收集**被设成 [FaceMode.NORMAL] 的贴容器面**。
     *
     * ⚠️ 只沿 [PipeType.Category.ITEM] 走 —— 能量管 / 流体管一根都不串。
     * 排除抽取源自己（不能抽出来又放回去）。
     */
    private fun resolveTargets(conduit: ConduitBlock, endpoint: Endpoint): List<CargoEndpoint> {
        val result = ArrayList<CargoEndpoint>()

        ConduitGraph.forEachReachable(
            start = conduit.block,
            category = PipeType.Category.ITEM,
            accept = { block ->
                // 候选：任何"看起来是容器"的非管方块
                CargoEndpoint.isContainer(block)
            },
            consumer = { block ->
                // ⚠️ 不能是抽取源自己（否则"抽出来又放回去"），但**允许**是同一根管的另一个容器：
                // 一根管完全可以在 A 面抽、B 面送。
                if (block == endpoint.source.block) return@forEachReachable
                // 同一个容器只收一次（多条管贴着它时会被多次访问）
                if (result.any { it.block == block }) return@forEachReachable

                // 找出是哪个管段、哪个面朝它
                val (attachedConduit, toward) = attachedNormalFace(block) ?: return@forEachReachable
                // ⚠️ 那一面必须是**真的连着的**（没被扳手禁用、红石允许）。
                // attachedNormalFace 只保证"是物品管 + 模式为 NORMAL"，
                // 还得过 connectsTo 这道关（它同时管着红石与 MachinePortGuard）。
                if (!attachedConduit.isConnectedOn(toward)) return@forEachReachable

                val target = CargoEndpoint.of(block) ?: return@forEachReachable
                if (target.size <= 0) return@forEachReachable
                result += target
            }
        )
        return result
    }

    /**
     * 一个容器被哪根物品管的哪个 [FaceMode.NORMAL] 面贴着。
     *
     * 返回 `(管段, 管子朝向容器的那个面)`；没有就返回 null。
     */
    private fun attachedNormalFace(container: org.bukkit.block.Block): Pair<ConduitBlock, BlockFace>? {
        for (face in CARDINAL_FACES) {
            val neighbor = container.getRelative(face)
            val conduit = rebarAt(neighbor) as? ConduitBlock ?: continue
            if (conduit.pipeType.category != PipeType.Category.ITEM) continue
            // 从管子的角度看过去：面朝容器的那一面
            val towardContainer = face.oppositeFace
            if (conduit.faceMode(towardContainer) != FaceMode.NORMAL) continue
            return conduit to towardContainer
        }
        return null
    }

    // ---------------------------------------------------------------- 排序 / 过滤

    private fun orderTargets(conduit: ConduitBlock, targets: List<CargoEndpoint>): List<CargoEndpoint> {
        // 抽哪个面的分配模式 —— 用抽取面的设置（Pipez 的 distribution 也是挂在面级的）
        val mode = extractorDistribution(conduit) ?: Distribution.DEFAULT
        return when (mode) {
            Distribution.NEAREST -> targets.sortedBy { distanceSq(conduit, it) }
            Distribution.FURTHEST -> targets.sortedByDescending { distanceSq(conduit, it) }
            Distribution.ROUND_ROBIN -> {
                val start = cursors.getOrDefault(conduit.block.position, 0)
                val size = targets.size
                val rotated = (0 until size).map { targets[(start + it) % size] }
                cursors[conduit.block.position] = (start + 1) % size
                rotated
            }
            Distribution.RANDOM -> targets.shuffled()
        }
    }

    private fun extractorDistribution(conduit: ConduitBlock): Distribution? {
        val endpoint = extractors[conduit.block.position] ?: return null
        return conduit.distribution(endpoint.face)
    }

    private fun distanceSq(conduit: ConduitBlock, target: CargoEndpoint): Double {
        val a = conduit.block.location
        val b = target.block.location
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * 本面的过滤器：列表为空 = 全放行；否则按 [FilterMode] 判定。
     *
     * 过滤器里存的是**物品实例**，比对用 `isSimilar`（忽略数量、保留附魔等元数据）。
     */
    private fun passesFilter(conduit: ConduitBlock, face: BlockFace, stack: ItemStack): Boolean {
        val filters = conduit.filters(face)
        if (filters.isEmpty()) return true
        val matches = filters.any { it.isSimilar(stack) }
        return when (conduit.filterMode(face)) {
            FilterMode.WHITELIST -> matches
            FilterMode.BLACKLIST -> !matches
        }
    }
}

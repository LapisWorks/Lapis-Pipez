package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/**
 * 防止**把一台机器自己的多个电口接进同一张管网** —— 也就是防止"自己给自己刷电"。
 *
 * ## 问题（用户实测）
 *
 * 拿一个空储能盒 + 一个满储能盒，把它们的**输出口和输入口全接进同一张管网**，
 * 两个盒子很快就都满了 —— 凭空生电。
 *
 * 原因：我们的管段节点是 `ElectricConnectorNode`（**纯导通**，不消耗不生产），
 * 所以"机器的输出口 → 管网 → 同一个机器的输入口"就是一条**短路**。
 * Rebar 的 `ElectricNetwork.distributePowerToConsumers` 里**没有任何防自环的机制**：
 * 它只是把每个 producer 的可用功率分给每个 consumer，再由 `tick()` 调
 * `producer.powerTakeHandler` 扣减。机器把自己的 out 和 in 都挂上以后，
 * 自己发出来的电又回流进自己（以及另一台），于是总量越滚越多。
 *
 * （同样的问题用 Rebar 自己的电线连也能复现 —— 这是 Rebar 电力模型的空缺，
 * 不是我们独有的。但我们的管道让它变得太容易触发，所以在插件侧兜一道。）
 *
 * ## 规则
 *
 * **同一张管网（= 同一个同类管连通分量）里，一台机器最多只接一个端口。**
 *
 * 具体到每一格：把"本分量里所有贴着这台机器、且那一面真有端口"的
 * `(管格位置, 面)` 收集起来，取**字典序最小的那一个**作为唯一出口；
 * 只有它允许连，其余一律不连（渲染上也不画胳膊）。
 *
 * 为什么按**连通分量**而不是"整台机器全局"：
 * 一台机器完全可以"输出接管网 A、输入接管网 B"，那是两套独立系统，没有短路。
 * 只有"两个端口落在同一张网里"才短路。
 *
 * ## 缓存
 *
 * 判据只跟拓扑有关，跟 tick 无关，所以可以缓存；拓扑一变
 * （[ConduitNetwork.markAround] 被调用）就整表清空，下次用到再算。
 *
 * ⚠️ 只对**能量管**生效。流体/物品侧没有这种"自发自收"的短路问题，
 * 不做限制以免误伤。
 */
object MachinePortGuard {

    /** 单次 BFS 的访问上限，防止超大管网把事件处理拖垮。 */
    private const val MAX_SCAN = 4096

    private val cache = HashMap<Key, Boolean>()

    private data class Key(val x: Int, val y: Int, val z: Int, val face: BlockFace)

    /** 缓存里的一条"出口"：某个管格 + 它朝机器的那个面。 */
    private data class Port(val x: Int, val y: Int, val z: Int, val faceOrdinal: Int)

    /**
     * [conduit] 在 [face] 那一面能不能连到机器上。
     *
     * 返回 false = "这一面不许连"（因为这台机器在这张网里已经有别的出口了）。
     */
    fun allows(conduit: ConduitBlock, face: BlockFace): Boolean {
        // 只对能量管生效
        if (conduit.pipeType.category != PipeType.Category.ENERGY) return true

        val machine = conduit.block.getRelative(face)
        val key = Key(conduit.block.x, conduit.block.y, conduit.block.z, face)
        cache[key]?.let { return it }

        val winner = findWinner(conduit, machine)
        val ok = winner != null &&
            winner.x == conduit.block.x &&
            winner.y == conduit.block.y &&
            winner.z == conduit.block.z &&
            winner.faceOrdinal == face.ordinal

        cache[key] = ok
        return ok
    }

    /** 拓扑变了就清空缓存（[ConduitNetwork.markAround] 里调）。 */
    fun invalidate() = cache.clear()

    /** 插件卸载 / 单测用。 */
    fun clear() = cache.clear()

    // ---------------------------------------------------------------- 内部

    /**
     * 在 [start] 所在的**同类管连通分量**里，找出贴着 [machine] 的所有"候选出口"，
     * 返回字典序最小的那一个。
     *
     * 遍历只沿同类管、且不越过断路的面 —— 和渲染/连线用的是同一套连通性。
     */
    private fun findWinner(start: ConduitBlock, machine: Block): Port? {
        val visited = HashSet<BlockPosition>()
        val queue = ArrayDeque<Block>()
        queue.addLast(start.block)

        var winner: Port? = null
        var count = 0

        while (queue.isNotEmpty() && count < MAX_SCAN) {
            val b = queue.removeFirst()
            if (!visited.add(b.position)) continue
            count++

            val conduit = rebarAt(b) as? ConduitBlock ?: continue
            if (conduit.pipeType != start.pipeType) continue

            for (f in conduit.portFacesTo(machine)) {
                val candidate = Port(b.x, b.y, b.z, f.ordinal)
                if (winner == null || less(candidate, winner)) winner = candidate
            }

            for (f in CARDINAL_FACES) {
                if (conduit.isDisabled(f)) continue
                queue.addLast(b.getRelative(f))
            }
        }
        return winner
    }

    /** 先比坐标（x→y→z），再比面序号 —— 纯字典序，保证全局唯一且稳定。 */
    private fun less(a: Port, b: Port): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        if (a.z != b.z) return a.z < b.z
        return a.faceOrdinal < b.faceOrdinal
    }
}

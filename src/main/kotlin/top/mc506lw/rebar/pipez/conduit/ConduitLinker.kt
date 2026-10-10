package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.block.interfaces.ElectricRebarBlock
import io.github.pylonmc.rebar.electricity.nodes.ElectricNode
import io.github.pylonmc.rebar.electricity.nodes.ElectricPortEntity
import io.github.pylonmc.rebar.entity.EntityStorage
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

/**
 * 把能量管接到"机器"（实现了 [ElectricRebarBlock] 的方块）的**电力端口**上。
 *
 * ## 为什么必须精确到"端口在哪一面"
 *
 * 机器是个方块，它可能只在**某几个面**上有端口（比如一台机器的电口开在北面）。
 * 管道必须接在"那一面真有个端口"的地方：
 * - 那一面有端口 → 连上，且在渲染上画一条胳膊过去；
 * - 那一面没有端口 → **就当它是空气**，不画胳膊、不连边。
 *
 * 这就是用户要的"连接到机器的能量端口处，没有能量端口就不自动连接"。
 *
 * ## 端口在哪一面：只能从实体位置反推
 *
 * Rebar 没有"给我第 X 面的端口节点"这种 API：
 * `ElectricPortSpec` 里明明有 `face` 字段，但**建完实体就丢了** ——
 * 既不在方块 PDC 里，也不在 `ElectricNode` 上，只有那个 `ElectricPortEntity`
 * 的**展示实体世界坐标**还带着方向信息。
 *
 * 端口实体的坐标（反编译 `ElectricPortEntity` 构造函数得到）是：
 * ```
 * 方块中心 + face.direction × radius × 1.01
 * ```
 * 所以"偏移方向就是端口所在的面"，取绝对值最大的那个轴即可。
 *
 * ## 三档状态，而不是两档
 *
 * 这是"连着连着就断"的关键。旧实现只有"能解析 / 不能解析"两态，
 * 于是**端口实体还没加载**（区块加载时序、同 tick 内先后顺序）被当成"没有端口"，
 * 合法的边就被断掉了 —— 而且没有任何事件会在端口加载后把它连回来。
 *
 * 现在区分 [PortState.PRESENT] / [PortState.PENDING] / [PortState.ABSENT]：
 * - PENDING 的定义是"`heldEntities` 里有 `port_*` 这个名字，但实体还没进 `EntityStorage`"。
 *   名字是**纯 map 查询**，实体没加载也读得到 —— 这就是区分两态的依据。
 * - 调用方对 PENDING 的处理是"保持现状"（渲染照画、网络别断），而不是"当没有"。
 */
object ConduitLinker {

    /** 端口实体名的前缀。见类注释 —— 来自 Rebar 反编译，改动需重新核对。 */
    private const val PORT_NAME_PREFIX = "port_"

    /**
     * 已解析出的"机器某个面上的端口"。
     *
     * 存的是**节点 UUID** 而不是对象：`ElectricNode` 会随网络合并被替换，
     * 存对象可能握着一个已经失效的引用。UUID 永远有效，用的时候再查一次。
     */
    private val cache = HashMap<PortKey, UUID>()

    private data class PortKey(val x: Int, val y: Int, val z: Int, val face: BlockFace)

    /**
     * **已经学到**的"节点 → 它在哪一面"。
     *
     * 为什么需要它：端口实体没加载时，光靠名字只能知道"有端口"，
     * **不知道在哪一面**。旧实现因此把"机器任意一面有端口"当成"每个面都有端口"，
     * 于是没有端口的那一面也被判成 PENDING —— 既画出一条连到机器侧面的假胳膊，
     * 又因为 PENDING 会进"保护名单"而**永远断不掉**（用户："侧面也能连接""没法断开"）。
     *
     * 现在只要**曾经**解析出某个端口的所在面，就记在这里；之后即便实体没加载，
     * 也能按面判断：这一面到底有没有端口。
     */
    private val knownFace = HashMap<UUID, BlockFace>()

    /** 端口在某一面的加载状态。 */
    enum class PortState {
        /** 找到了端口，坐标也读得到 → 可以连。 */
        PRESENT,

        /** `heldEntities` 里有端口名字，但实体还没加载 → **保持现状，别断**。 */
        PENDING,

        /** 那一面压根没端口 → 不连，也不该画胳膊。 */
        ABSENT
    }

    /** 端口实体对应的 `heldEntities` 名字。 */
    fun portHeldEntityName(nodeId: UUID): String = PORT_NAME_PREFIX + nodeId

    /**
     * 找出 [machine] 贴在 [face] 那一面上的端口节点；找不到（含 PENDING）返回 null。
     *
     * 只应该用于**真正要连边**的地方（`refreshElectric`）——
     * 判断"该不该画胳膊 / 该不该断"请用 [portLoadState]。
     */
    fun portNodeFacing(machine: ElectricRebarBlock, face: BlockFace): ElectricNode? {
        val uuid = portUuidFacing(machine, face) ?: return null
        // 用 UUID 反查当前节点对象（网络合并后旧引用会失效）
        return machine.electricNodes.firstOrNull { it.id == uuid }
    }

    /** [machine] 在 [face] 那一面**有没有**端口（含 PENDING）。 */
    fun hasPortFacing(machine: ElectricRebarBlock, face: BlockFace): Boolean =
        portLoadState(machine, face) != PortState.ABSENT

    /**
     * 端口在 [face] 那一面的**加载状态**。渲染与连线共用这一个判据。
     *
     * ## 为什么 PENDING 必须**按面**判断
     *
     * 旧实现是"机器只要有一个 `port_*` 名字就整机 PENDING"，
     * 于是"电口开在顶面的机器"在**西面**也会被判成 PENDING →
     * 西面画出一条假胳膊、并且永远进"保护名单"断不掉。
     *
     * 现在先把**能解析的端口**的所在面都学下来（[ensureFacesResolved]），
     * 再逐个面判断：
     * - 解析得出 → [PortState.PRESENT]
     * - 名字在、但**面未知**（实体从没加载过）→ 无法排除本面 → [PortState.PENDING]
     * - 名字在、且**已知在别的面** → 与本面无关
     * - 名字都不在 → [PortState.ABSENT]
     */
    fun portLoadState(machine: ElectricRebarBlock, face: BlockFace): PortState {
        if (portUuidFacing(machine, face) != null) return PortState.PRESENT
        ensureFacesResolved(machine)

        var unknownFace = false
        for (node in machine.electricNodes) {
            if (!machine.isHeldEntityPresent(portHeldEntityName(node.id))) continue
            when (knownFace[node.id]) {
                face -> return PortState.PENDING   // 这个端口就在这一面，只是实体没加载
                null -> unknownFace = true         // 面未知 —— 排除不了本面
                else -> Unit                       // 已知在别的面 → 和本面无关
            }
        }
        return if (unknownFace) PortState.PENDING else PortState.ABSENT
    }

    /**
     * 把 [machine] 上**当前能解析**的端口所在面记进 [knownFace]。
     *
     * 只对"实体已加载"的端口有效；没加载的自然学不到（那就只能当未知处理）。
     */
    private fun ensureFacesResolved(machine: ElectricRebarBlock) {
        for (node in machine.electricNodes) {
            if (knownFace.containsKey(node.id)) continue
            val entity = portEntityOf(machine, node.id) ?: continue
            directionOf(entity.entity.location.toVector().subtract(centerOf(machine)))
                ?.let { knownFace[node.id] = it }
        }
    }

    /**
     * 丢掉 [machine] 的全部缓存。机器被拆 / 区块卸载时调用。
     */
    fun invalidate(machine: ElectricRebarBlock) {
        val x = machine.block.x
        val y = machine.block.y
        val z = machine.block.z
        cache.keys.removeIf { it.x == x && it.y == y && it.z == z }
        // 这个机器的节点可能已经不存在了，顺手把学到的面也丢掉（防 UUID 泄漏）
        machine.electricNodes.forEach { knownFace.remove(it.id) }
    }

    /** 清空（插件卸载 / 单测用）。 */
    fun clear() {
        cache.clear()
        knownFace.clear()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 反推 [face] 那一面的端口节点 UUID（命中缓存就直接返回）。
     *
     * 只看"能不能解析出 UUID"，**不关心 PENDING** —— 供只需要"有 / 没有"的地方用。
     */
    private fun portUuidFacing(machine: ElectricRebarBlock, face: BlockFace): UUID? {
        val key = PortKey(machine.block.x, machine.block.y, machine.block.z, face)
        cache[key]?.let { uuid ->
            // 节点还活着、实体也在 → 直接复用（端口不会自己换面）
            if (machine.electricNodes.any { it.id == uuid } && portEntityOf(machine, uuid) != null) {
                return uuid
            }
            cache.remove(key)
        }

        val center = centerOf(machine)
        for (node in machine.electricNodes) {
            val entity = portEntityOf(machine, node.id) ?: continue
            val dir = directionOf(entity.entity.location.toVector().subtract(center)) ?: continue
            // 顺手把"这个端口在哪一面"学下来 —— 以后实体没加载也能按面判断
            knownFace[node.id] = dir
            if (dir == face) {
                cache[key] = node.id
                return node.id
            }
        }
        return null
    }

    /**
     * 拿端口实体。**只按名字查** —— 名字是纯 map 查询，实体加载与否都能查到；
     * 查到的 UUID 再走 `EntityStorage` 拿对象（没加载就是 null）。
     *
     * 为什么不用"扫 heldEntities + `EntityStorage.getAs`"当主路径：
     * 那样在实体未加载时报废，无法区分"没端口"和"没加载"。
     * 名字查得到、对象拿不到 = PENDING，这个信息只有名字查法才有。
     *
     * 名字规则万一变了（Rebar 升级），退路是扫 `heldEntities` 逐个匹配 node。
     */
    private fun portEntityOf(machine: ElectricRebarBlock, node: UUID): ElectricPortEntity? {
        machine.getHeldEntityUuid(portHeldEntityName(node))
            ?.let { uuid -> EntityStorage.getAs(ElectricPortEntity::class.java, uuid) }
            ?.let { return it }

        return machine.heldEntities.values.asSequence()
            .mapNotNull { EntityStorage.getAs(ElectricPortEntity::class.java, it) }
            .firstOrNull { it.node.id == node }
    }

    private fun centerOf(machine: ElectricRebarBlock): Vector =
        machine.block.location.toCenterLocation().toVector()

    /**
     * 由相对方块中心的偏移量判断方向（取绝对值最大的那个轴）。
     *
     * 端口实体的坐标 = 方块中心 + `face.direction × radius × 1.01`
     * （反编译 `ElectricPortEntity` 构造函数得到），所以偏移方向**就是**端口所在的面。
     */
    private fun directionOf(offset: Vector): BlockFace? {
        val x = abs(offset.x)
        val y = abs(offset.y)
        val z = abs(offset.z)
        val max = maxOf(x, y, z)
        if (max < 1.0e-4) return null
        return if (max == x) {
            if (offset.x > 0) BlockFace.EAST else BlockFace.WEST
        } else if (max == y) {
            if (offset.y > 0) BlockFace.UP else BlockFace.DOWN
        } else {
            if (offset.z > 0) BlockFace.SOUTH else BlockFace.NORTH
        }
    }
}

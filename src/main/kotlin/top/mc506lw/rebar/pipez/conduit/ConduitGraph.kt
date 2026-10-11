package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block

/**
 * 沿管段做的图遍历 —— 物品搬运路由的唯一实现。
 *
 * ## 为什么不用 Rebar 自带的 `CargoRoutes`
 *
 * Rebar 的货运路由把**任何** `CargoRebarBlock` 当成路由终点
 * （`if (currentBlock is CargoRebarBlock) { endpoint = ...; break }`），
 * 所以一条管段链上的第一根就会被误判成目的地。而管段本来就不该是 `CargoRebarBlock`
 * —— 那会带来每方块一个协程 ticker，违背本附属"管段不 tick"的底线。
 *
 * 所以这里自己走。
 *
 * ## 为什么 `accept` 收 `Block` 而不是 `RebarBlock`
 *
 * 物品的"终点"是**容器**，而容器大多数是**原版方块**（箱子 / 桶 / 漏斗），
 * 根本不是 `RebarBlock`。所以判据必须能拿到裸 [Block]。
 *
 * ## 类型隔离
 *
 * 只沿**同类型**的 [ConduitBlock] 扩散：物品搬运传 [PipeType.Category.ITEM]，
 * 能量管 / 流体管一律当死路 —— 这就是用户要的
 * "能量管不和物品 / 流体管道自动连接"。
 */
object ConduitGraph {

    /** 单次搜索的访问上限，防止超大网络把 tick 拖垮。 */
    private const val MAX_VISITED = 4096

    /**
     * 从 [start] 的六个邻居出发做 BFS，把**所有**满足 [accept] 的非管段方块交给
     * [consumer]，然后返回走访过的方块数。
     *
     * 为什么是"交给 consumer"而不是"返回 List"：
     * 调用方（物品搬运）要在遍历中顺带算出"这个容器是被哪个管段的哪个面贴着的"，
     * 那需要知道**是从哪个方向走进来的**。用回调把控制权交回调用方，最省事。
     *
     * [category] 传 `null` 表示"任何 [ConduitBlock] 都走"（只给兼容逻辑用）。
     *
     * 会尊重断路：被扳手禁用的面不扩散（[ConduitBlock.isDisabled]）。
     * **不会**顺带判断红石 —— 那是 [ConduitBlock.connectsTo] 的职责，
     * 调用方拿到候选后再自行用它过滤（见 `ItemTransfer.attachedNormalFace`）。
     */
    fun forEachReachable(
        start: Block,
        category: PipeType.Category? = null,
        accept: (Block) -> Boolean,
        consumer: (Block) -> Unit
    ): Int {
        val visited = HashSet<BlockPosition>()
        val queue = ArrayDeque<Block>()
        for (face in CARDINAL_FACES) queue.addLast(start.getRelative(face))

        var count = 0
        while (queue.isNotEmpty() && count < MAX_VISITED) {
            val block = queue.removeFirst()
            if (!visited.add(block.position)) continue
            count++

            val rebar = rebarAt(block)
            if (rebar is ConduitBlock) {
                // 类型不符的管段是**死路**：不扩散、也不当成终点
                if (category != null && rebar.pipeType.category != category) continue
                for (face in CARDINAL_FACES) {
                    if (rebar.isDisabled(face)) continue
                    queue.addLast(block.getRelative(face))
                }
            } else if (accept(block)) {
                consumer(block)
            }
        }
        return count
    }
}

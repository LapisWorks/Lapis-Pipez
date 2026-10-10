package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block

/**
 * 沿管段做的图遍历。
 *
 * 物品侧不能用 Rebar 自带的 `CargoRoutes`：它把**任何** `CargoRebarBlock` 当成路由终点
 * （`if (currentBlock is CargoRebarBlock) { endpoint = ...; break }`），
 * 所以一条管段链上的第一根就会被误判成目的地。而管段本来就不该是 `CargoRebarBlock`
 * （那会带来每方块一个协程 ticker，违背"管段不 tick"）。
 *
 * 所以这里自己走：从起点出发，只沿**同类型**的 [ConduitBlock] 扩散，
 * 遇到第一个满足条件的非管段方块就返回。
 */
object ConduitGraph {

    /** 单次搜索的访问上限，防止超大网络把 tick 拖垮。 */
    private const val MAX_VISITED = 4096

    /**
     * 从 [start] 出发做 BFS，返回第一个满足 [accept] 的方块。
     *
     * 会尊重扳手禁用的方向（禁用的面不扩散）。
     *
     * ## ⚠️ 类型隔离：只沿 [category] 的管扩散
     *
     * [category] 为 null 表示"任何 [ConduitBlock] 都走"（旧行为，只给兼容逻辑用）。
     * 传具体类别时，**只沿那一类的管扩散** —— 这就是用户要的
     * "能量管不和物品 / 流体管道自动连接"：物品端点只会顺着**物品管**走，
     * 而物品管目前还不存在，所以物品端点**暂时不会**顺着能量管 / 流体管乱跑。
     *
     * 等做"物品管道"里程碑时，`ItemEndpoint` 传 `PipeType.Category.ITEM` 即可
     * —— 那时候物品管才会被纳入，能量管 / 流体管依旧被排除在外。
     */
    fun findFirst(
        start: Block,
        category: PipeType.Category? = null,
        accept: (RebarBlock) -> Boolean
    ): RebarBlock? {
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
            } else if (rebar != null && accept(rebar)) {
                return rebar
            }
        }
        return null
    }
}

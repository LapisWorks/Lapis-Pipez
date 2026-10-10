package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block

/**
 * 安全访问 Rebar 方块。
 *
 * `BlockStorage.get(...)` 在区块未加载时会抛：
 *
 * ```
 * java.lang.IllegalArgumentException: You can only get Rebar blocks in loaded chunks
 * ```
 *
 * 而管道天生是"跨区块"的：邻居可能落在未加载的区块里（区块卸载、玩家跑远、
 * 异步加载都会撞上）。所以**所有对邻居 / 位置查询 Rebar 方块的地方都必须走这里**，
 * 不要直接调 `BlockStorage.get`。
 *
 * `BlockPosition.isChunkLoaded` 只做检查、不会加载区块，所以这里是安全的。
 */
fun rebarAt(block: Block): RebarBlock? =
    if (block.position.isChunkLoaded) BlockStorage.get(block) else null

fun rebarAt(pos: BlockPosition): RebarBlock? =
    if (pos.isChunkLoaded) BlockStorage.get(pos) else null

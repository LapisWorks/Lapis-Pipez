package top.mc506lw.rebar.pipez.conduit

import org.bukkit.block.BlockFace

/** 只包含六个正方向的固定顺序，形状掩码的位序就是它的下标。 */
val CARDINAL_FACES: List<BlockFace> = listOf(
    BlockFace.UP,
    BlockFace.DOWN,
    BlockFace.NORTH,
    BlockFace.SOUTH,
    BlockFace.EAST,
    BlockFace.WEST
)

/** 该方向在禁用掩码里的位。 */
fun BlockFace.conduitBit(): Int = 1 shl CARDINAL_FACES.indexOf(this)

/** Bukkit 方向 → 纯 Kotlin 方向。传斜角/自身会抛异常，调用方应先过滤。 */
fun BlockFace.toConduitFace(): ConduitFace = when (this) {
    BlockFace.UP -> ConduitFace.UP
    BlockFace.DOWN -> ConduitFace.DOWN
    BlockFace.NORTH -> ConduitFace.NORTH
    BlockFace.SOUTH -> ConduitFace.SOUTH
    BlockFace.EAST -> ConduitFace.EAST
    BlockFace.WEST -> ConduitFace.WEST
    else -> throw IllegalArgumentException("Not a cardinal face: $this")
}

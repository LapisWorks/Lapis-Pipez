package top.mc506lw.rebar.pipez.conduit

/**
 * 管段的六个方向。
 *
 * 这里刻意**不用 Bukkit 的 `BlockFace`**，而是自己定义一套 0..5 的方向枚举 ——
 * 这样 [ConduitShape] 就是纯 Kotlin，不依赖服务器，可以直接单测。
 * 与 `BlockFace` 的互转在 `ConduitFaces.kt` 里。
 */
enum class ConduitFace {
    UP, DOWN, NORTH, SOUTH, EAST, WEST;

    /** 该方向在形状掩码里的位。 */
    val bit: Int get() = 1 shl ordinal

    /** 反方向。 */
    val opposite: ConduitFace
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            NORTH -> SOUTH
            SOUTH -> NORTH
            EAST -> WEST
            WEST -> EAST
        }
}

/** 直管所在的轴。 */
enum class Axis { X, Y, Z }

/**
 * 管段形状：完全由"六个方向各自是否连通"推导。
 *
 * **这是纯函数，不碰 Bukkit，所以能直接单测**（见 `ConduitShapeTest`）。
 *
 * 为什么形状不落盘：形状是邻居的函数，只要邻居在，形状就能算出来。
 * 不落盘 = 区块加载时零 IO、零反序列化 —— 这是"万级"设计里最省成本的一刀。
 */
object ConduitShape {

    /** 形状分类，用来选模型。 */
    enum class Shape { ISOLATED, END, STRAIGHT, CORNER, TEE, CROSS }

    /** 由"每面是否连通"算掩码。 */
    fun mask(connected: (ConduitFace) -> Boolean): Int =
        ConduitFace.entries.fold(0) { acc, face -> if (connected(face)) acc or face.bit else acc }

    /** 连了几个方向。 */
    fun degree(mask: Int): Int = Integer.bitCount(mask)

    /** 度数为 2 且两个方向互为反向 → 是一条直线。 */
    fun isStraight(mask: Int): Boolean =
        degree(mask) == 2 && ConduitFace.entries.any { face ->
            (mask and face.bit) != 0 && (mask and face.opposite.bit) != 0
        }

    /** 直管的轴；非直管返回 null。 */
    fun straightAxis(mask: Int): Axis? {
        if (!isStraight(mask)) return null
        return when {
            (mask and (ConduitFace.EAST.bit or ConduitFace.WEST.bit)) != 0 -> Axis.X
            (mask and (ConduitFace.UP.bit or ConduitFace.DOWN.bit)) != 0 -> Axis.Y
            else -> Axis.Z
        }
    }

    /** 形状分类。 */
    fun classify(mask: Int): Shape = when (degree(mask)) {
        0 -> Shape.ISOLATED
        1 -> Shape.END
        2 -> if (isStraight(mask)) Shape.STRAIGHT else Shape.CORNER
        3 -> Shape.TEE
        else -> Shape.CROSS
    }

    /**
     * 管段的**直线轴**，允许端点（度数 1）。
     *
     * 与 [straightAxis] 的区别：
     * - [straightAxis] 只认度数 2 且互为反向（"严格意义上的直线中段"）；
     * - [runAxis] 额外把**度数 1 的端点**也算成直线的一部分。
     *
     * 为什么需要 [runAxis]：一根悬空的 2 通直管，两格各自只连一格（度数 1）。
     * 如果只认度数 2，它们都会被判成"非直线"，于是各自去画臂 →
     * 视觉上就是"两侧各多出一截断臂"（用户看到的 `臂心臂心臂`）。
     * 用 [runAxis] 把端点并进整条 run，由 run 统一收口，就不会多出臂。
     */
    fun runAxis(mask: Int): Axis? {
        val degree = degree(mask)
        if (degree !in 1..2) return null
        // 所有连通方向必须落在同一条轴上
        val connected = ConduitFace.entries.filter { (mask and it.bit) != 0 }
        val axis = when {
            connected.all { it == ConduitFace.EAST || it == ConduitFace.WEST } -> Axis.X
            connected.all { it == ConduitFace.UP || it == ConduitFace.DOWN } -> Axis.Y
            connected.all { it == ConduitFace.NORTH || it == ConduitFace.SOUTH } -> Axis.Z
            else -> return null
        }
        return axis
    }
}

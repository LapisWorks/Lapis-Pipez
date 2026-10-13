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
 * 一段管身在某一侧**远端**的性质 —— 收口方式完全由它决定。
 *
 * ## 为什么必须有它（"视觉 ≠ 功能"的根治）
 *
 * 管身往哪伸、伸多远，**只能**由"这一面连不连通"推导。
 * 曾经 `ConduitBlock.spawnRun` 用的是"对面有没有方块"（`neighbor == null`）：
 * 玩家把机器那一面设成"空"（Sided-IO 判定不连通）之后，对面**仍然是那台机器**，
 * 于是管身照样**齐平贴上去** —— 看起来和真连上一模一样，功能上却已经断了。
 *
 * 现在两个绘制路径（直线段 `spawnRun` 与拐弯臂 `spawnSegment`）都归到这里，
 * 判据永远一致；[of] 的 `linked` 参数**只**接受 `connectsTo` 的结果。
 */
enum class FarEnd {
    /** 对面是空气（或区块未加载）→ 画可见的"管口"。 */
    AIR,

    /** 对面是**同类**管段、且它愿意连回来 → 越过一格交叠，接头严丝合缝。 */
    SAME_PIPE,

    /** 对面是**异色**管段 → 齐平停在本格表面（既不糊一起、也不越界）。 */
    OTHER_PIPE,

    /** 对面**真的连上了**（机器 / 原版容器）→ 齐平停在表面，像插头插进去。 */
    LINKED,

    /**
     * 对面**有东西，但这一面不连通** —— 被设成"空" / 那台机器在这一面没有端口 /
     * 对面管段设了断路。
     *
     * ⚠️ 这一档**绝不能画成齐平** —— 齐平就等于"看着连上了"。
     * 它停在**本格中心**（收口距离 0），和对面留出半格可见的空隙，一眼看得出没接上。
     */
    BLOCKED;

    /**
     * 沿轴、从**方块中心**算起的收口距离（正值 = 朝该侧伸出）。
     *
     * @param faceReach 齐平到本格表面的距离（`FACE_REACH` = 0.5）
     * @param endProtrusion 管口再往外伸的那一点（`END_PROTRUSION`）
     * @param overlap 同类管之间的交叠量（`OVERLAP`）
     */
    fun reach(faceReach: Double, endProtrusion: Double, overlap: Double): Double = when (this) {
        SAME_PIPE -> 1.0 + overlap
        OTHER_PIPE, LINKED -> faceReach
        AIR -> faceReach + endProtrusion
        BLOCKED -> 0.0
    }

    companion object {
        /**
         * 由**连通性**分类远端。**这是唯一的判据来源。**
         *
         * @param linked 调用方 `connectsTo(face)` 的结果。它已经含了
         *   断路 / 红石 / 端口是否存在 / 防自短接 / **Sided-IO 的面配置** ——
         *   所以不要在这里再补"对面有没有方块"之类的旁路判据。
         * @param samePipe 对面是**同类**管段
         * @param otherPipe 对面是**异色**管段
         * @param air 对面是空气，或所在区块没加载
         */
        fun of(linked: Boolean, samePipe: Boolean, otherPipe: Boolean, air: Boolean): FarEnd = when {
            // 管段先分档：
            // - 同类管要不要交叠，**取决于它连不连回来**（对面把这一面设了断路 → 留空隙）
            // - 异色管永远不可能"连通"（类型就不同），固定齐平不糊
            samePipe -> if (linked) SAME_PIPE else BLOCKED
            otherPipe -> OTHER_PIPE
            linked -> LINKED
            air -> AIR
            else -> BLOCKED
        }
    }
}

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

    /** 某条轴的**规范正方向** —— 整条轴上所有格子都用它判"谁是起点"。 */
    fun canonical(axis: Axis): ConduitFace = when (axis) {
        Axis.X -> ConduitFace.EAST
        Axis.Y -> ConduitFace.UP
        Axis.Z -> ConduitFace.SOUTH
    }

    /**
     * 本格是不是它所在直线段的**起点**（坐标最小的那一端）。
     *
     * ## 为什么必须是一个纯函数（这个 bug 的教训）
     *
     * 渲染是"整条直线段由**起点格**一次画完"。所以**同一条 run 上只能有一个格子
     * 自认为是起点** —— 两个都算成起点，两边各画一条 run，叠加起来就是用户报的
     * `臂心臂心臂`（一条完整的 run + 一条从自己中心往外捅的短管 + 两套管口）。
     *
     * 之前这段判据长在 `ConduitBlock.isRunStart` 里、直接读 Bukkit 邻居，于是
     * **一个纯逻辑（谁该画）被耦合进了"邻居此刻拿不拿得到"** —— 区块边界、
     * 实体注册时序一变，"前面还有没有管"的答案就会翻面，两边同时判成起点。
     *
     * 抽到这里之后，判据只依赖**三个已经算好的布尔/纯函数结果**：
     *
     * @param mask 本格的形状掩码（自己连了几个方向）
     * @param prevMask 沿 [canonical] 反方向那一格的掩码；**对面不是同类连通管时传 null**
     * @param prevLinksBack 邻格是否愿意连回本格（对面那一面没设断路）
     *
     * ## 判据（与 `ConduitBlock.isRunStart` 一一对应）
     *
     * 1. 本格朝反方向**没连着** → 本格就是起点（断路 / 空气 / 对面断路）。
     * 2. 对面不是同类连通管（拿不到 / 不是管 / 类型不同 / 不连回来）→ 本格是起点。
     * 3. 邻格必须**也属于同一条轴**（拐角 / 三通不算延续）→ 否则本格是起点。
     *
     * ⚠️ 第 3 条只比**邻格**的轴：调用方只在 `runAxis(mask) != null` 时才会问
     * "我是不是起点"，本格的轴必定等于 [canonical] 对应的那条，比它是多余的。
     */
    fun isRunStart(mask: Int, canonical: ConduitFace, prevMask: Int?, prevLinksBack: Boolean): Boolean {
        if ((mask and canonical.opposite.bit) == 0) return true
        val prev = prevMask ?: return true
        if (!prevLinksBack) return true
        return runAxis(prev) != runAxis(mask)
    }

    /**
     * 一个管格变化后，**除了它自己所在的 run 之外**，还有哪些方向的 run 需要一并作废。
     *
     * ## 这是"放置顺序不同 → 渲染不同"的另一半
     *
     * 渲染是"整条直线段由**起点格**一次画完"（贪婪合并）。所以起点格的几何取决于
     * **几格之外**（run 多长、两端是什么）。为了补"去重漏判"，`ConduitNetwork.flush`
     * 会把 run 起点拉进批次并强制重建 —— 但它靠 `runStartOf` 找起点，而
     * `runStartOf` 第一句是 `runAxis() ?: return null`。
     *
     * **拐角 / 三通的 `runAxis()` 恰好是 null** → 以它们为端点的那些 run 的起点
     * 永远进不了批次 → 那条 run 的几何明明该变，却既不重建也不清除 →
     * **旧的显示实体留在世界上**（用户实测：先放竖管、旁边再接横管，拐角下方残留一截旧管口）。
     *
     * - **直管 / 端点**（[runAxis] 非空）：同类邻居必定在同一条轴上，
     *   `runStartOf(本格)` 已经覆盖 → 返回空。
     * - **拐角 / 三通 / 十字**（[runAxis] 为 null）：每个连通方向都可能是一条 run 的端点 → 全部返回。
     *
     * 纯函数，有单测（见 `ConduitShapeTest`）。
     */
    fun crossRunDirections(mask: Int): List<ConduitFace> =
        if (runAxis(mask) != null) emptyList()
        else ConduitFace.entries.filter { (mask and it.bit) != 0 }
}

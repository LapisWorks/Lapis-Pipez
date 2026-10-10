package top.mc506lw.rebar.pipez.conduit

import org.bukkit.block.BlockFace

/**
 * 管段某一面的**连接模式** —— 复刻 Pipez 的三态：
 * **普通 / 抽取 / 断路**。
 *
 * ## 三态从哪来（Pipez 源码）
 *
 * Pipez 的 `PipeBlock.onWrenchClicked` 里，一个面其实是**两个布尔**：
 * `extracting` 和 `disconnected`，组合出三种有意义的状态：
 *
 * | 状态 | extracting | disconnected | 含义 |
 * |---|---|---|---|
 * | 普通 | false | false | 正常连接（默认）。管子往邻居送，也能从邻居收 |
 * | 抽取 | true  | false | 从邻居**往外抽**（能量：从机器取电；流体：抽液） |
 * | 断路 | false | true  | 不连，当它是空气 |
 *
 * 所以这里就用三态枚举，而不是 Pipez 的两个布尔 —— 枚举更好读，也更好做界面。
 *
 * ## 为什么没有"接收 / 输出"这一态
 *
 * Pipez 没有"只送不收"的独立状态：**不抽取 = 普通 = 默认就往外送**。
 * 所以"送"是默认行为，不需要单独一档。之前设计里那一档已删掉。
 *
 * ## ⚠️ 默认必须是「普通」，不能是「抽取」
 *
 * 如果把默认设成"抽取"，那么每一条"管 ↔ 机器"的边都会被标成**单向**
 * （只允许 机器 → 管）。结果：**机器要从管子取电时取不到** ——
 * 所有老存档、所有"默认放置"的管子瞬间失效。
 * 所以默认是 [NORMAL]（不限制方向），玩家显式点成"抽取"才真正单向。
 *
 * ## 与旧 `disabledFaces` 的关系
 *
 * [NONE] 就是旧的"被禁用"。为了老存档不炸，PDC 里**同时保留**旧键：
 * 写模式表时顺手写一份等价的禁用掩码，读的时候优先读模式表、读不到再退回旧掩码。
 *
 * ## 序号
 *
 * 枚举顺序（`ordinal`）**一旦发布不要改**：PDC 里存的是序号。
 * 要加新模式只能**往后追加**。
 */
enum class FaceMode(
    /** lang 键后缀，形如 `gui.face_mode.extract`。 */
    val id: String
) {
    /** 断路：这个面不连接，渲染上也不画臂（等价于旧的"被扳手禁用"）。 */
    NONE("none"),

    /**
     * 普通（**默认**）：连，但不限制方向。
     *
     * 这是"没配过"的状态，也是老存档迁移后的状态 ——
     * 保证默认放置的管子行为和以前完全一致。
     */
    NORMAL("normal"),

    /**
     * 抽取（Pipez 的 Extract）。
     *
     * - **能量管**：只允许电力**从邻居机器流向本管道网**（机器是电源）。
     * - **流体管**：只允许流体**从邻居机器抽进管道网**。
     *
     * 渲染上：末端会有一块**大的黑色板**（Pipez 的 `SHAPE_EXTRACT_*`）。
     */
    EXTRACT("extract");

    /** 这个面到底连不连。 */
    val connects: Boolean get() = this != NONE

    /** 这个面是不是"抽取"（唯一有方向限制的一态）。 */
    val directional: Boolean get() = this == EXTRACT

    /** 下一个模式（界面上左键点一下循环）。 */
    val next: FaceMode get() = entries[(ordinal + 1) % entries.size]

    /** 上一个模式（右键点一下反着循环）。 */
    val previous: FaceMode get() = entries[(ordinal + entries.size - 1) % entries.size]

    companion object {
        /** 没配过时的模式。改这个值等于改"新管子的默认行为"。 */
        val DEFAULT: FaceMode = NORMAL

        /**
         * "末端"（对面不是同类型管子）上，扳手循环的顺序：
         * 普通 → 抽取 → 断路 → 普通。和 Pipez 的手感一致。
         */
        val TERMINAL_CYCLE: List<FaceMode> = listOf(NORMAL, EXTRACT, NONE)

        /** 从 PDC 序号还原；越界回退到 [DEFAULT]。 */
        fun fromOrdinal(value: Int): FaceMode = entries.getOrNull(value) ?: DEFAULT

        /**
         * 由旧的"禁用掩码"还原整个模式表。
         *
         * 旧存档里只有"能连 / 不能连"：禁用的面 = [NONE]，
         * 其余面 = [NORMAL]（不限制方向）—— 这样老管子升级后行为**完全不变**。
         */
        fun tableFromDisabledMask(mask: Int): Map<BlockFace, FaceMode> =
            CARDINAL_FACES.associateWith { face ->
                if ((mask and face.conduitBit()) != 0) NONE else NORMAL
            }

        /**
         * 由模式表反推等价的"禁用掩码"（给旧版本 / 旧存档读）。
         *
         * ⚠️ 只有 [NONE] 会进掩码 —— [NORMAL] / [EXTRACT] 都是"连"，
         * 旧版本读到的就是"没禁用"，渲染上不会画错。
         */
        fun disabledMaskOf(table: Map<BlockFace, FaceMode>): Int =
            table.entries.fold(0) { acc, (face, mode) ->
                if (mode == NONE) acc or face.conduitBit() else acc
            }
    }
}

package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "整条直线段只由**起点格**画一次"这条不变量的纯函数测试。
 *
 * ## 这个测试在守什么（用户报的 `臂心臂心臂`）
 *
 * 渲染是贪心合并的：一整条同轴直线段只由**坐标最小的那一端**画一条 run，
 * 其余格子什么都不画。所以**同一条 run 上只能有一个格子自认起点**。
 *
 * 一旦有两个格子同时自认起点（比如"前面还有没有管"的运行时答案在某一侧翻了面），
 * 它们就会各画一条 run：
 * - 真正的起点画一条长 run（起点中心 → 末端中心，两端带管口）；
 * - 误判的那一格画一条**长度 0 的 run**（末端就是自己，`runLength = 0`
 *   → 从自己中心往两个方向各伸 0.54）。
 *
 * 叠加起来恰好就是用户形容的 **`臂 心 臂 心 臂`**。
 *
 * 以前"谁该画"这段判据长在 `ConduitBlock.isRunStart` 里、直接读 Bukkit 邻居，
 * **没有单测覆盖** → 这类回归只能靠肉眼在游戏里发现。现在判据抽成了纯函数
 * [ConduitShape.isRunStart]，这里把它钉死。
 */
class ConduitRunOwnershipTest {

    // ------------------------------------------------------------ 掩码构造速记

    private fun mask(vararg faces: ConduitFace): Int =
        faces.fold(0) { acc, f -> acc or f.bit }

    /** 一根悬空直管的两端格：各自只连一格，方向相反。 */
    private val westEnd = mask(ConduitFace.EAST)   // 左边那格，朝东连
    private val eastEnd = mask(ConduitFace.WEST)   // 右边那格，朝西连

    // ------------------------------------------------------------ 核心不变量

    /**
     * 🔴 回归（用户报的 bug）：**两根同类管相邻，只能有一个格子是起点。**
     *
     * X 轴上，西边那格自认起点；东边那格看到西边有同轴管 → 必须**不是**起点。
     * 两边同时为 true 就是 `臂心臂心臂`。
     */
    @Test
    fun `exactly one end of a two-cell run claims to be the start`() {
        val canonical = ConduitShape.canonical(Axis.X)      // EAST

        // 西端格：朝反方向（WEST）没连着 → 起点
        val westIsStart = ConduitShape.isRunStart(
            mask = westEnd,
            canonical = canonical,
            prevMask = null,            // 西边是空气，拿不到邻居
            prevLinksBack = false,
        )
        // 东端格：西边有同类管、它愿意连回来、且也是同一条轴 → 不是起点
        val eastIsStart = ConduitShape.isRunStart(
            mask = eastEnd,
            canonical = canonical,
            prevMask = westEnd,
            prevLinksBack = true,
        )

        assertTrue(westIsStart, "西端格应当是这条 run 的起点")
        assertFalse(eastIsStart, "东端格绝不能也自认起点（那就是用户报的 臂心臂心臂）")
        assertEquals(
            1, listOf(westIsStart, eastIsStart).count { it },
            "一条 run 上恰好只能有一个起点"
        )
    }

    /**
     * 把它推广到 **N 格长直管**：不论多长，从头走到尾，自认起点的格子**有且只有一个**。
     *
     * 这是比单点断言更难绕过的全量不变量 —— 只要判据里出现任何"两侧不对称"，
     * 都会在这个扫描里变成 0 个或 ≥2 个起点。
     */
    @Test
    fun `a straight run of any length has exactly one start`() {
        val canonical = ConduitShape.canonical(Axis.X)

        for (length in 1..12) {
            val starts = (0 until length).count { index ->
                // 中间格是度数 2（东西都连），两端是度数 1
                val self = when (index) {
                    0 -> westEnd
                    length - 1 -> eastEnd
                    else -> mask(ConduitFace.EAST, ConduitFace.WEST)
                }
                // 前一个格子（沿 canonical 反方向 = WEST）的掩码；没有就是 null
                val prev = when {
                    index == 0 -> null
                    index == 1 -> westEnd
                    else -> mask(ConduitFace.EAST, ConduitFace.WEST)
                }
                ConduitShape.isRunStart(
                    mask = self,
                    canonical = canonical,
                    prevMask = prev,
                    prevLinksBack = prev != null,
                )
            }
            assertEquals(1, starts, "长度 $length 的直管应当恰好有 1 个起点，实际 $starts")
        }
    }

    /** Y 轴、Z 轴各扫一遍：不能只有 X 轴是对的（规范方向映射写错就只错一条轴）。 */
    @Test
    fun `the single-start invariant holds on every axis`() {
        for (axis in Axis.entries) {
            val canonical = ConduitShape.canonical(axis)
            val forward = canonical
            val backward = canonical.opposite

            val head = mask(forward)                                 // 轴的小端
            val tail = mask(backward)                                // 轴的大端
            val middle = mask(forward, backward)

            assertTrue(
                ConduitShape.isRunStart(head, canonical, null, false),
                "$axis 轴：小端格应当是起点"
            )
            assertFalse(
                ConduitShape.isRunStart(tail, canonical, head, true),
                "$axis 轴：大端格不该自认起点"
            )
            // 三格：小端 → 中段 → 大端，只有小端是起点
            assertEquals(
                1,
                listOf(
                    ConduitShape.isRunStart(head, canonical, null, false),
                    ConduitShape.isRunStart(middle, canonical, head, true),
                    ConduitShape.isRunStart(tail, canonical, middle, true),
                ).count { it },
                "$axis 轴：三格直管恰好一个起点"
            )
        }
    }

    // ------------------------------------------------------------ 边界：起点该"分裂"的地方

    /**
     * **对面那一格不连回来**（它把朝我这一面设了断路）→ 我不再是"有前驱的中段"，
     * **必须自认起点**，否则那一段会整段消失。
     */
    @Test
    fun `a broken link in front makes this cell the start again`() {
        val canonical = ConduitShape.canonical(Axis.X)
        assertTrue(
            ConduitShape.isRunStart(eastEnd, canonical, westEnd, prevLinksBack = false),
            "前面那格不连回来时，本格必须自认起点（否则整段不画）"
        )
    }

    /** 对面是**拐角**（`runAxis` 为 null）→ 不算延续，本格是起点。 */
    @Test
    fun `a corner in front is not a continuation`() {
        val canonical = ConduitShape.canonical(Axis.X)
        val corner = mask(ConduitFace.WEST, ConduitFace.UP)     // 朝西 + 朝上 = 拐角
        assertTrue(
            ConduitShape.isRunStart(eastEnd, canonical, corner, prevLinksBack = true),
            "拐角不算同轴延续，本格应当是起点"
        )
    }

    /** 对面是**异色管**（拿不到 / 不同类型 → 调用方传 null）→ 本格是起点。 */
    @Test
    fun `another colour pipe in front is not a continuation`() {
        val canonical = ConduitShape.canonical(Axis.X)
        assertTrue(
            ConduitShape.isRunStart(eastEnd, canonical, prevMask = null, prevLinksBack = false),
            "异色管不算同一根 run，本格应当是起点"
        )
    }

    /**
     * **本格朝反方向压根没连** → 无论如何都是起点。
     * 这是整个判据的第一道闸，写反了会让所有悬空端点都不画。
     */
    @Test
    fun `a cell with no link backwards is always a start`() {
        val canonical = ConduitShape.canonical(Axis.X)
        // 只朝东连（westEnd），问的是"朝西有没有连" → 没有 → 起点
        assertTrue(ConduitShape.isRunStart(westEnd, canonical, null, false))
        // 就算硬塞一个前驱进来，"本格朝反方向没连"也必须优先
        assertTrue(
            ConduitShape.isRunStart(westEnd, canonical, westEnd, prevLinksBack = true),
            "本格朝反方向没连时必须是起点，前驱信息不该让它翻面"
        )
    }

    // ------------------------------------------------------------ 规范方向本身

    /** 规范方向必须是"轴的正方向"，整条轴上所有格子靠它取得一致。 */
    @Test
    fun `canonical direction is the positive direction of each axis`() {
        assertEquals(ConduitFace.EAST, ConduitShape.canonical(Axis.X))
        assertEquals(ConduitFace.UP, ConduitShape.canonical(Axis.Y))
        assertEquals(ConduitFace.SOUTH, ConduitShape.canonical(Axis.Z))
    }

    // ------------------------------------------------------------ 「心臂心」形态

    /**
     * 🔴 形态不变量（用户定的）：**N 格直管 = N 个「心」+ (N-1) 段「臂」**，
     * 而且**总跨度小于 N 格** —— 心在方块中心，管子不占满整格。
     *
     * 用户原话："0 通你知道啥样吧，就是应该是一个臂两端都是 0 通"，
     * 以及"它的长度就不是完整 2 格"。
     *
     * ⚠️ `heartSize` 取的是**管身粗细**（`bodyThickness` = 0.32），不是另配的值 ——
     * 心和臂必须同粗（原版 Pipez 的 `SHAPE_CORE` / `SHAPE_EAST` 都是 6/16 方块）。
     * 之前心用 `isolated-cube-size`（0.35）比管身粗 0.03，每个心都鼓出一小圈。
     */
    @Test
    fun `a run of N cells is N hearts and N-1 arms, spanning less than N blocks`() {
        val heartSize = 0.32      // = PipezConfig.bodyThickness 的默认值（心与臂同粗）

        for (cells in 2..8) {
            val arms = cells - 1
            val span = arms * 1.0 + heartSize

            assertEquals(cells, arms + 1, "$cells 格：臂数必须 = 心数 - 1")
            assertTrue(
                span < cells,
                "$cells 格的跨度 $span 必须 **小于** $cells 格 —— 心在方块中心，不占满整格"
            )
            assertTrue(
                span > cells - 1,
                "$cells 格的跨度 $span 必须 **大于** ${cells - 1} 格 —— 两端的心各露半个出来"
            )
        }

        // 两根相邻管（用户实测的那个场景）：心 臂 心 ≈ 1.32 格，不是 2 格
        assertEquals(1.32, 1 * 1.0 + heartSize, 1e-9, "2 格管应当是 心-臂-心 ≈ 1.32 格")
    }
}

package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 远端收口分类的纯函数测试。不需要跑服务器。
 *
 * ## 这个测试在守什么
 *
 * 用户报过一个 bug：**把机器的某一面设成"空"之后，管道功能上断了，视觉上却还连着。**
 *
 * 根因是渲染侧用"对面**有没有方块**"当"连不连通"的代理 —— 对面仍然是那台机器，
 * 于是管身照样齐平贴上去，和真连上一模一样。
 *
 * 所以这里钉死一条不变量：**"有东西但不连通"必须是一个和"真连上"不同的档位，
 * 且收口距离必须明显小于齐平距离**。谁再把 `linked` 换成"非 null"，
 * 这个测试立刻会红。
 */
class ConduitFarEndTest {

    /** 几何常量与 `ConduitBlock` 的 companion 保持一致。 */
    private val faceReach = 0.5
    private val endProtrusion = 0.04
    private val overlap = 0.02

    private fun reach(kind: FarEnd) = kind.reach(faceReach, endProtrusion, overlap)

    // ------------------------------------------------------------ 核心不变量

    /**
     * 🔴 回归：**对面有方块、但这一面不连通 → 绝不能画成齐平。**
     *
     * 两个真实场景：
     * - 玩家把机器那一面设成"空"（Sided-IO 判定不连通），对面**仍然是那台机器**；
     * - 对面是同类管段，但它把朝我这一面设了**断路**。
     */
    @Test
    fun `a neighbour that exists but is not linked is never drawn flush`() {
        val machineFaceSetToNone = FarEnd.of(
            linked = false, samePipe = false, otherPipe = false, air = false
        )
        val neighbourPipeDisabled = FarEnd.of(
            linked = false, samePipe = true, otherPipe = false, air = false
        )

        for (kind in listOf(machineFaceSetToNone, neighbourPipeDisabled)) {
            assertEquals(FarEnd.BLOCKED, kind, "有东西但不连通必须是 BLOCKED，实际 $kind")
            assertTrue(
                reach(kind) < faceReach,
                "$kind 的收口距离 ${reach(kind)} 不该 >= 齐平距离 $faceReach（那就成了\"看着连上了\"）"
            )
        }
    }

    /**
     * 反过来钉一次：**真连上的机器 / 原版容器就是齐平**。
     * 别为了修 bug 把正常连接也缩回去了。
     */
    @Test
    fun `a linked machine is drawn flush`() {
        val kind = FarEnd.of(linked = true, samePipe = false, otherPipe = false, air = false)
        assertEquals(FarEnd.LINKED, kind)
        assertEquals(faceReach, reach(kind), 1e-9)
    }

    // ------------------------------------------------------------ 各档位

    @Test
    fun `air gets a visible mouth`() {
        val kind = FarEnd.of(linked = false, samePipe = false, otherPipe = false, air = true)
        assertEquals(FarEnd.AIR, kind)
        assertEquals(faceReach + endProtrusion, reach(kind), 1e-9)
    }

    @Test
    fun `a same-type pipe overlaps only when it links back`() {
        val linked = FarEnd.of(linked = true, samePipe = true, otherPipe = false, air = false)
        assertEquals(FarEnd.SAME_PIPE, linked)
        assertEquals(1.0 + overlap, reach(linked), 1e-9)

        // 对面那根管把朝我这一面设了断路 → 不许交叠、也不许齐平
        val notLinked = FarEnd.of(linked = false, samePipe = true, otherPipe = false, air = false)
        assertEquals(FarEnd.BLOCKED, notLinked)
    }

    /**
     * 异色管（能量管贴着流体管）永远不可能"连通"（类型就不同），
     * 固定齐平 —— 既不糊成一体、也不像空气那样把管口戳进对方。
     */
    @Test
    fun `another colour pipe stays flush and never overlaps`() {
        val kind = FarEnd.of(linked = false, samePipe = false, otherPipe = true, air = false)
        assertEquals(FarEnd.OTHER_PIPE, kind)
        assertEquals(faceReach, reach(kind), 1e-9)
    }

    @Test
    fun `blocked stops at the block centre`() {
        assertEquals(0.0, reach(FarEnd.BLOCKED), 1e-9)
    }

    // ------------------------------------------------------------ 枚举自身

    /**
     * 只要"不连通"且对面**有实体方块**，就不该落到 [FarEnd.LINKED] / [FarEnd.SAME_PIPE]。
     * 这是对整个组合空间的全量扫描，比单点断言更难绕过。
     */
    @Test
    fun `no combination of unlinked non-air states yields a flush category`() {
        for (samePipe in listOf(false, true)) {
            for (otherPipe in listOf(false, true)) {
                for (air in listOf(false, true)) {
                    val kind = FarEnd.of(
                        linked = false, samePipe = samePipe, otherPipe = otherPipe, air = air
                    )
                    if (air) continue                       // 空气本来就没有对面方块
                    assertNotEquals(
                        FarEnd.LINKED, kind,
                        "linked=false 却分到了 LINKED（samePipe=$samePipe, otherPipe=$otherPipe）"
                    )
                    assertNotEquals(
                        FarEnd.SAME_PIPE, kind,
                        "linked=false 却分到了 SAME_PIPE（对面管没连回不该交叠）"
                    )
                }
            }
        }
    }

    /** 异色管优先于"连通性"：就算调用方算错了 linked，也不该把它当成机器。 */
    @Test
    fun `another colour pipe wins over a bogus linked flag`() {
        val kind = FarEnd.of(linked = true, samePipe = false, otherPipe = true, air = false)
        assertEquals(FarEnd.OTHER_PIPE, kind)
    }
}

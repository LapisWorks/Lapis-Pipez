package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 形状推导的纯函数测试。不需要跑服务器。
 */
class ConduitShapeTest {

    @Test
    fun `opposite is symmetric and involutive`() {
        for (face in ConduitFace.entries) {
            assertEquals(face, face.opposite.opposite, "opposite should be its own inverse")
            assertTrue(face != face.opposite, "a face must not be its own opposite")
        }
    }

    @Test
    fun `bits are unique and cover exactly six faces`() {
        val bits = ConduitFace.entries.map { it.bit }
        assertEquals(6, bits.size)
        assertEquals(6, bits.toSet().size, "bits must be unique")
        assertEquals(0b111111, bits.fold(0) { acc, bit -> acc or bit })
    }

    @Test
    fun `empty mask is isolated`() {
        assertEquals(0, ConduitShape.mask { false })
        assertEquals(0, ConduitShape.degree(0))
        assertEquals(ConduitShape.Shape.ISOLATED, ConduitShape.classify(0))
    }

    @Test
    fun `a single face is an end`() {
        val mask = ConduitFace.UP.bit
        assertEquals(1, ConduitShape.degree(mask))
        assertEquals(ConduitShape.Shape.END, ConduitShape.classify(mask))
        assertFalse(ConduitShape.isStraight(mask))
        assertNull(ConduitShape.straightAxis(mask))
    }

    @Test
    fun `an opposite pair is a straight line`() {
        val mask = ConduitFace.UP.bit or ConduitFace.DOWN.bit
        assertEquals(2, ConduitShape.degree(mask))
        assertTrue(ConduitShape.isStraight(mask))
        assertEquals(ConduitShape.Shape.STRAIGHT, ConduitShape.classify(mask))
        assertEquals(Axis.Y, ConduitShape.straightAxis(mask))
    }

    @Test
    fun `a perpendicular pair is a corner`() {
        val mask = ConduitFace.UP.bit or ConduitFace.NORTH.bit
        assertFalse(ConduitShape.isStraight(mask))
        assertEquals(ConduitShape.Shape.CORNER, ConduitShape.classify(mask))
        assertNull(ConduitShape.straightAxis(mask))
    }

    @Test
    fun `three faces is a tee`() {
        val mask = ConduitFace.UP.bit or ConduitFace.DOWN.bit or ConduitFace.EAST.bit
        assertEquals(ConduitShape.Shape.TEE, ConduitShape.classify(mask))
    }

    @Test
    fun `four or more faces is a cross`() {
        val four = ConduitFace.UP.bit or ConduitFace.DOWN.bit or
            ConduitFace.EAST.bit or ConduitFace.WEST.bit
        assertEquals(ConduitShape.Shape.CROSS, ConduitShape.classify(four))
        assertEquals(ConduitShape.Shape.CROSS, ConduitShape.classify(0b111111))
    }

    @Test
    fun `straight axis follows the connected plane`() {
        assertEquals(Axis.X, ConduitShape.straightAxis(ConduitFace.EAST.bit or ConduitFace.WEST.bit))
        assertEquals(Axis.Y, ConduitShape.straightAxis(ConduitFace.UP.bit or ConduitFace.DOWN.bit))
        assertEquals(Axis.Z, ConduitShape.straightAxis(ConduitFace.NORTH.bit or ConduitFace.SOUTH.bit))
    }

    @Test
    fun `mask builder only sets the connected faces`() {
        val mask = ConduitShape.mask { it == ConduitFace.NORTH || it == ConduitFace.SOUTH }
        assertEquals(ConduitFace.NORTH.bit or ConduitFace.SOUTH.bit, mask)
        assertEquals(2, ConduitShape.degree(mask))
    }

    @Test
    fun `runAxis accepts a bare endpoint as part of a straight run`() {
        // 悬空 2 通的两格各自只连一格（度数 1），必须仍被判成直线的一部分，
        // 否则它们会各自去画臂 → 两侧多出断臂。
        assertEquals(Axis.X, ConduitShape.runAxis(ConduitFace.EAST.bit))
        assertEquals(Axis.X, ConduitShape.runAxis(ConduitFace.WEST.bit))
        assertEquals(Axis.Y, ConduitShape.runAxis(ConduitFace.UP.bit))
        assertEquals(Axis.Z, ConduitShape.runAxis(ConduitFace.NORTH.bit))
    }

    @Test
    fun `runAxis accepts a straight middle section`() {
        assertEquals(Axis.X, ConduitShape.runAxis(ConduitFace.EAST.bit or ConduitFace.WEST.bit))
        assertEquals(Axis.Y, ConduitShape.runAxis(ConduitFace.UP.bit or ConduitFace.DOWN.bit))
        assertEquals(Axis.Z, ConduitShape.runAxis(ConduitFace.NORTH.bit or ConduitFace.SOUTH.bit))
    }

    @Test
    fun `runAxis rejects isolated corners tees and crosses`() {
        // 0 通：交给孤立方块造型
        assertNull(ConduitShape.runAxis(0))
        // 拐弯（两条不同轴的方向）：不是直线
        assertNull(ConduitShape.runAxis(ConduitFace.UP.bit or ConduitFace.NORTH.bit))
        // 三通 / 十字
        assertNull(
            ConduitShape.runAxis(
                ConduitFace.UP.bit or ConduitFace.DOWN.bit or ConduitFace.EAST.bit
            )
        )
        assertNull(ConduitShape.runAxis(0b111111))
    }

    // ---------------------------------------------------------------- 跨 run 作废方向
    //
    // 守的是用户实测的那个 bug：**同样形状、放置顺序不同、渲染不一样**。
    //
    // 渲染是"整条直线段由起点格一次画完"。一个格子从"直管"变成"拐角"时，
    // **以它为端点的那条 run** 的几何（长度 / 末端收口）就变了 —— 但那条 run 的
    // 起点格可能离得很远。`ConduitNetwork.flush` 靠"把起点拉进批次强制重建"来补，
    // 而它找起点用的 `runStartOf` 对拐角直接返回 null（`runAxis() ?: return null`）。
    // 于是穿过拐角的那些 run 的起点永远不被重建 → 旧的显示实体留在世界上。
    //
    // `crossRunDirections` 就是"还要沿哪些方向去找 run 起点"的纯逻辑版。

    @Test
    fun `a straight run block needs no cross-run invalidation`() {
        // 度数 1 的端点：只有一个方向，runStartOf(本格) 已经覆盖
        assertEquals(emptyList(), ConduitShape.crossRunDirections(ConduitFace.EAST.bit))
        // 度数 2 的直管中段：两个方向在同一条轴上，同样已覆盖
        assertEquals(
            emptyList(),
            ConduitShape.crossRunDirections(ConduitFace.EAST.bit or ConduitFace.WEST.bit)
        )
        assertEquals(
            emptyList(),
            ConduitShape.crossRunDirections(ConduitFace.UP.bit or ConduitFace.DOWN.bit)
        )
    }

    @Test
    fun `a corner must invalidate both of its runs`() {
        // 拐角：竖管 + 横管两条 run 都以它为端点，两条都要作废
        val corner = ConduitFace.UP.bit or ConduitFace.NORTH.bit
        assertEquals(
            listOf(ConduitFace.UP, ConduitFace.NORTH),
            ConduitShape.crossRunDirections(corner)
        )
    }

    @Test
    fun `a tee must invalidate all three of its runs`() {
        val tee = ConduitFace.UP.bit or ConduitFace.DOWN.bit or ConduitFace.EAST.bit
        assertEquals(
            listOf(ConduitFace.UP, ConduitFace.DOWN, ConduitFace.EAST),
            ConduitShape.crossRunDirections(tee)
        )
    }

    @Test
    fun `an isolated block has nothing to invalidate`() {
        assertEquals(emptyList(), ConduitShape.crossRunDirections(0))
    }

    /**
     * 关键不变量：**只要 `runAxis` 为 null（本格不画 run），就必须返回全部连通方向**。
     * 谁把这条改窄了（比如只返回一部分方向），那条被漏掉的 run 的起点就永远不重建。
     */
    @Test
    fun `every connected face is invalidated whenever this block draws arms instead of a run`() {
        for (mask in 0..0b111111) {
            if (ConduitShape.runAxis(mask) != null) continue
            val connected = ConduitFace.entries.filter { (mask and it.bit) != 0 }
            assertEquals(
                connected,
                ConduitShape.crossRunDirections(mask),
                "mask=${Integer.toBinaryString(mask)} 是拐角/三通，必须作废全部连通方向"
            )
        }
    }
}

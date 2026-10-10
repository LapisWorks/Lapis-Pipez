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
}

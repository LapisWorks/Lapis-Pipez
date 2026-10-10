package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 档位与升级模块键的纯函数测试。
 */
class ConduitTierTest {

    @Test
    fun `tiers are ordered from basic to ultimate`() {
        val tiers = ConduitTier.entries
        assertEquals(ConduitTier.BASIC, tiers.first())
        assertEquals(ConduitTier.ULTIMATE, tiers.last())
        for (i in 0 until tiers.size - 1) {
            assertEquals(tiers[i + 1], tiers[i].next)
        }
        assertNull(tiers.last().next)
    }

    @Test
    fun `capacity strictly increases with tier`() {
        val capacities = ConduitTier.entries.map { it.baseCapacity }
        assertEquals(capacities.sorted(), capacities)
        assertEquals(capacities.size, capacities.toSet().size, "capacities must be distinct")
    }

    @Test
    fun `item key round trips through fromItemKey`() {
        for (tier in ConduitTier.entries) {
            val key = ConduitTier.ITEM_PREFIX + tier.id
            assertEquals(tier, ConduitTier.fromItemKey(key))
        }
    }

    @Test
    fun `fromItemKey rejects keys without the prefix`() {
        assertNull(ConduitTier.fromItemKey("conduit"))
        assertNull(ConduitTier.fromItemKey("wrench"))
        assertNull(ConduitTier.fromItemKey("upgrade_nonexistent"))
    }

    @Test
    fun `fromId round trips`() {
        for (tier in ConduitTier.entries) {
            assertNotNull(ConduitTier.fromId(tier.id))
            assertEquals(tier, ConduitTier.fromId(tier.id))
        }
        assertNull(ConduitTier.fromId("nope"))
    }

    @Test
    fun `fromOrdinal falls back to basic when out of range`() {
        assertEquals(ConduitTier.BASIC, ConduitTier.fromOrdinal(0))
        assertEquals(ConduitTier.ULTIMATE, ConduitTier.fromOrdinal(ConduitTier.entries.size - 1))
        assertEquals(ConduitTier.BASIC, ConduitTier.fromOrdinal(-1))
        assertEquals(ConduitTier.BASIC, ConduitTier.fromOrdinal(999))
    }
}

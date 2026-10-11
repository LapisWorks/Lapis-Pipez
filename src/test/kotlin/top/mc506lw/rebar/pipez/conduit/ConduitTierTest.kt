package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * 档位解锁矩阵必须和 Pipez 官方文档**逐格一致**。
     *
     * Pipez 的 `Upgrade` 枚举（见官方 wiki / DeepWiki "Upgrade Tier Capability Matrix"）：
     *
     * | 档位 | 红石 | 分配 | 过滤 |
     * |---|---|---|---|
     * | Basic    | ✅ | ❌ | ❌ |
     * | Improved | ✅ | ✅ | ❌ |
     * | Advanced | ✅ | ✅ | ✅ |
     * | Ultimate | ✅ | ✅ | ✅ |
     *
     * 这是"物品管插升级模块才能用过滤 / 分配"这条规则的数据依据 ——
     * 一旦有人改了 [ConduitTier] 的布尔标志，这个测试会立刻失败。
     */
    @Test
    fun `tier capability matrix matches Pipez`() {
        // 每一档 → (红石, 分配, 过滤)
        val expected = mapOf(
            ConduitTier.BASIC to Triple(true, false, false),
            ConduitTier.IMPROVED to Triple(true, true, false),
            ConduitTier.ADVANCED to Triple(true, true, true),
            ConduitTier.ULTIMATE to Triple(true, true, true)
        )
        for ((tier, flags) in expected) {
            val actual = Triple(tier.canChangeRedstone, tier.canChangeDistribution, tier.canChangeFilter)
            assertEquals(flags, actual, "capability mismatch for $tier")
        }
    }

    /** 能力必须**随档位单调不减** —— 高一档不可能反而更少。 */
    @Test
    fun `capabilities never regress as tier increases`() {
        val tiers = ConduitTier.entries
        for (i in 1 until tiers.size) {
            val lower = tiers[i - 1]
            val higher = tiers[i]
            if (lower.canChangeRedstone) assertTrue(higher.canChangeRedstone)
            if (lower.canChangeDistribution) assertTrue(higher.canChangeDistribution)
            if (lower.canChangeFilter) assertTrue(higher.canChangeFilter)
        }
    }
}

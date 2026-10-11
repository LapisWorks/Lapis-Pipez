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

    /**
     * 物品搬运速度 / 个数必须**逐格对齐 Pipez 的 ServerConfig 默认值**。
     *
     * 数值来自 `de.maxhenkel.pipez.ServerConfig`（已核对源码）：
     * - `item_pipe.speed.no_upgrade` = 20, `item_pipe.amount.no_upgrade` = 4
     * - `item_pipe.speed.basic` = 15, `amount.basic` = 8
     * - `item_pipe.speed.improved` = 10, `amount.improved` = 16
     * - `item_pipe.speed.advanced` = 5, `amount.advanced` = 32
     * - `item_pipe.speed.ultimate` = 1, `amount.ultimate` = 64
     *
     * 用户明确要求"升级模型也能给物品抽取速度升级" —— 这个表就是那份"升级真的提速"的数据。
     */
    @Test
    fun `item speed and amount match Pipez defaults`() {
        val expected = mapOf(
            ConduitTier.BASIC to (15 to 8),
            ConduitTier.IMPROVED to (10 to 16),
            ConduitTier.ADVANCED to (5 to 32),
            ConduitTier.ULTIMATE to (1 to 64)
        )
        for ((tier, pair) in expected) {
            assertEquals(pair.first, tier.itemSpeed, "itemSpeed mismatch for $tier")
            assertEquals(pair.second, tier.itemAmount, "itemAmount mismatch for $tier")
        }
        // 无升级那一档（不在枚举里，是常量）
        assertEquals(20, ConduitTier.NO_UPGRADE_ITEM_SPEED)
        assertEquals(4, ConduitTier.NO_UPGRADE_ITEM_AMOUNT)
    }

    /** 档位越高，物品搬运必须**不慢于**低档、一次搬得**不少于**低档。 */
    @Test
    fun `item transfer strictly improves with tier`() {
        // 无升级那一档要排在 BASIC 前面一起比
        val speed = listOf(ConduitTier.NO_UPGRADE_ITEM_SPEED) + ConduitTier.entries.map { it.itemSpeed }
        val amount = listOf(ConduitTier.NO_UPGRADE_ITEM_AMOUNT) + ConduitTier.entries.map { it.itemAmount }
        // speed 是"每 N tick 一次"，所以**越小越快**
        for (i in 1 until speed.size) {
            assertTrue(speed[i] <= speed[i - 1], "speed regressed at index $i: $speed")
        }
        for (i in 1 until amount.size) {
            assertTrue(amount[i] >= amount[i - 1], "amount regressed at index $i: $amount")
        }
        // 速度/个数都必须 > 0，否则 tick 里 `% speed` 会除零、或根本搬不动
        assertTrue(speed.all { it > 0 }, "every speed must be positive")
        assertTrue(amount.all { it > 0 }, "every amount must be positive")
    }
}

package top.mc506lw.rebar.pipez.conduit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PipeType] 的纯函数测试：键的往返、类别隔离。
 *
 * 不需要跑服务器 —— 这些全是枚举上的静态逻辑。
 */
class PipeTypeTest {

    @Test
    fun `item key round trips for every type`() {
        for (type in PipeType.entries) {
            val key = PipeType.ITEM_PREFIX + type.id
            assertEquals(type, PipeType.fromItemKey(key), "round trip failed for $key")
        }
    }

    @Test
    fun `legacy conduit key does not resolve to a pipe type`() {
        // 旧键 `conduit` 没有 `pipe_` 前缀 —— 必须返回 null，
        // 让 ConduitBlock 走"回退成能量管"的老存档兼容路径。
        assertNull(PipeType.fromItemKey("conduit"))
    }

    @Test
    fun `unknown id resolves to null`() {
        assertNull(PipeType.fromId("nope"))
        assertNull(PipeType.fromItemKey("pipe_nope"))
    }

    @Test
    fun `every category is represented and all keys are unique`() {
        assertEquals(PipeType.Category.ENERGY, PipeType.ENERGY.category)
        assertEquals(PipeType.Category.FLUID, PipeType.FLUID.category)
        // 物品管已经落地：物品类别必须有对应条目，否则物品搬运无从路由。
        assertEquals(PipeType.Category.ITEM, PipeType.ITEM.category)

        val ids = PipeType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "pipe ids must be unique")

        // 三个类别各自至少有一种管道 —— 防止再出现"类别存在但没有条目"的死代码。
        for (category in PipeType.Category.entries) {
            assertTrue(
                PipeType.entries.any { it.category == category },
                "category $category has no pipe type"
            )
        }
    }
}

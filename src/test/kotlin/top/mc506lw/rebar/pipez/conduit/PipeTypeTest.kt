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
    fun `energy and fluid are different categories and never share a key`() {
        assertEquals(PipeType.Category.ENERGY, PipeType.ENERGY.category)
        assertEquals(PipeType.Category.FLUID, PipeType.FLUID.category)
        val ids = PipeType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "pipe ids must be unique")
    }

    @Test
    fun `item category exists but has no pipe type yet`() {
        // 物品类别先存在（供 ConduitGraph 表达"只走物品管"），
        // 但现在还没有对应条目 —— 这正是"物品端点暂不沿任何管段路由"的依据。
        val itemTypes = PipeType.entries.filter { it.category == PipeType.Category.ITEM }
        assertTrue(itemTypes.isEmpty(), "item pipe not implemented yet")
    }
}

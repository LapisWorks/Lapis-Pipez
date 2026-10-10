package top.mc506lw.rebar.pipez.bridge

import io.github.pylonmc.rebar.block.RebarBlock
import java.lang.reflect.Method
import xyz.xenondevs.invui.inventory.VirtualInventory

/**
 * 读取 Rebar 机器虚拟库存的**反射适配层**。
 *
 * ## 为什么需要反射
 *
 * `io.github.pylonmc.rebar.block.interfaces.VirtualInventoryRebarBlock` 在 Kotlin 层
 * 取不到它的 `virtualInventories`（和 `FluidManager`、`LogisticSlot` 是同一类问题），
 * 但字节码里 `getVirtualInventories()` 是公开方法，反射可以直接调。
 *
 * 返回的 `VirtualInventory` 本身是 **InvUI 的公开类**，拿到之后所有读写都走公开 API，
 * 不需要再反射。
 *
 * 代价与 [FluidBridge] 相同：走的是框架的非公开 API，Rebar 升级后要重新验证。
 */
object VirtualInventoryBridge {

    private const val BLOCK_INTERFACE =
        "io.github.pylonmc.rebar.block.interfaces.VirtualInventoryRebarBlock"

    private var interfaceClass: Class<*>? = null
    private var getInventoriesMethod: Method? = null

    var available: Boolean = false
        private set

    var failureReason: String? = null
        private set

    fun initialize(): Boolean = try {
        val cls = Class.forName(BLOCK_INTERFACE)
        interfaceClass = cls
        getInventoriesMethod = cls.getMethod("getVirtualInventories")
        available = true
        failureReason = null
        true
    } catch (t: Throwable) {
        available = false
        failureReason = "${t::class.java.simpleName}: ${t.message}"
        false
    }

    /**
     * 如果这个方块是 Rebar 机器且暴露了虚拟库存，返回它们；否则返回空表。
     *
     * 所有失败路径都返回空表 —— 读不到库存只是"这个容器不支持"，不该抛异常。
     */
    @Suppress("UNCHECKED_CAST")
    fun inventoriesOf(block: RebarBlock): Map<String, VirtualInventory> {
        if (!available) return emptyMap()
        val cls = interfaceClass ?: return emptyMap()
        if (!cls.isInstance(block)) return emptyMap()
        val method = getInventoriesMethod ?: return emptyMap()

        val result = runCatching { method.invoke(block) }.getOrNull() ?: return emptyMap()
        return result as? Map<String, VirtualInventory> ?: emptyMap()
    }
}

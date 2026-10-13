package top.mc506lw.rebar.pipez.integration

import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import top.mc506lw.rebar.pipez.conduit.ConduitNetwork
import top.mc506lw.rebar.pipez.conduit.PipeType
import top.mc506lw.rebar.sidedio.ChannelTypes
import top.mc506lw.rebar.sidedio.SidedIo
import top.mc506lw.rebar.sidedio.SidedIoFaceConfigChangedEvent

/**
 * 与 **Sided-IO** 的软桥接 —— 让机器的面配置成为管道的**权威**。
 *
 * ## 为什么要这一层
 *
 * Sided-IO 是"每面 I/O 配置"的基础设施。玩家把某个面设成 **"空"** 的意思就是
 * "这个口我不许任何东西进出"。但管道如果还照旧画一条胳膊过去，就成了
 * **"看着连了、其实没连"** —— 这正是用户报的那个问题。
 *
 * 所以管道在决定**渲染**和**接线**之前都该问一下 Sided-IO。
 *
 * ## 为什么是软依赖 + 两个类
 *
 * 管道不该因为一个可选的配置库而加载失败，所以 `plugin.yml` 里是
 * **`softDepend`** 而不是 `depend`，Gradle 里是 `compileOnly`。
 *
 * 但"软依赖"有个 JVM 层面的坑：**只要类被加载，它引用的类型就要能解析**。
 * 所以这里拆成两个类：
 *
 * | 类 | 直接引用 Sided-IO？ | 什么时候被加载 |
 * |---|---|---|
 * | [SidedIoBridge] | **否**（只查插件管理器） | 总是 |
 * | [SidedIoHook] | **是** | **只在 Sided-IO 装了的时候** |
 *
 * 这样 Sided-IO 缺席时 [SidedIoHook] 永远不会被碰，也就不会有 `NoClassDefFoundError`。
 */
internal object SidedIoBridge {

    /** Sided-IO 在不在。不在的话一切判定都放行（保持管道原本的行为）。 */
    val available: Boolean = runCatching {
        Bukkit.getPluginManager().isPluginEnabled("Sided-IO")
    }.getOrDefault(false)

    /**
     * 机器在 [face] 这一面允不允许接 [category] 的管道。
     *
     * ⚠️ [face] 是**机器自己的面**（不是管道那一侧的面）——
     * 调用方要传 `管道的 face.oppositeFace`。
     *
     * 没装 Sided-IO、或那个方块不归 Sided-IO 管 → **放行**。
     * 绝不能让"查不到"变成"把玩家的管道全断了"。
     */
    fun allows(block: Block, face: BlockFace, category: PipeType.Category): Boolean =
        if (!available) true else SidedIoHook.allows(block, face, category)

    /**
     * 装了就注册监听：**面配置一变就重算管道形状**。
     *
     * ## 为什么需要这一步（这是"接线判定"之外的另一半）
     *
     * [allows] 只在管道**自己**放下来 / 邻居变的时候被问一次。
     * 玩家之后把面从**输入**改成**空**，管道不会自己知道 ——
     * 那条胳膊会一直画着，又变回"看着连了其实没连"。
     *
     * 所以 Sided-IO 改配置时会发 [SidedIoFaceConfigChangedEvent]，
     * 我们收到就 [ConduitNetwork.markAround] 把那一圈管格标脏，下一 tick 重算。
     */
    fun register(plugin: Plugin) {
        if (!available) return
        SidedIoHook.register(plugin)
    }
}

/**
 * 真正调 Sided-IO 的那一层。**只在 [SidedIoBridge.available] 为真时被加载**。
 */
internal object SidedIoHook {

    fun allows(block: Block, face: BlockFace, category: PipeType.Category): Boolean {
        val type = when (category) {
            PipeType.Category.ITEM -> ChannelTypes.ITEM
            PipeType.Category.FLUID -> ChannelTypes.FLUID
            // ⚠️ 能量在 Sided-IO 里是**只展示**的通道（走 Rebar 电网络，没有方向），
            // `canConnect` 对它会直接放行 —— 这里不用特判，语义已经对齐。
            PipeType.Category.ENERGY -> ChannelTypes.ENERGY
        }
        return runCatching { SidedIo.canConnect(block, face, type) }.getOrDefault(true)
    }

    fun register(plugin: Plugin) {
        plugin.server.pluginManager.registerEvents(FaceConfigListener(), plugin)
    }

    /**
     * 面配置变了 → 重算那台机器周围管格的形状。
     *
     * [ConduitNetwork.markAround] 会把方块本身 + 四个水平邻居标脏，
     * 所以**紧贴机器的那些管格一定在范围内**（它们就在邻居位上）。
     * 剩下的合并与重绘由网络自己的 tick 批处理做。
     */
    private class FaceConfigListener : Listener {
        @EventHandler
        fun onFaceConfigChanged(event: SidedIoFaceConfigChangedEvent) {
            ConduitNetwork.markAround(event.block.position)
        }
    }
}

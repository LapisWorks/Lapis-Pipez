package top.mc506lw.rebar.pipez

import io.github.pylonmc.rebar.addon.RebarAddon
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.rebar.pipez.bridge.FluidBridge
import top.mc506lw.rebar.pipez.bridge.VirtualInventoryBridge
import top.mc506lw.rebar.pipez.cargo.ItemTransfer
import top.mc506lw.rebar.pipez.conduit.ConduitNetwork
import top.mc506lw.rebar.pipez.fluid.ConduitFluid
import top.mc506lw.rebar.pipez.listeners.ConduitLinkListener
import java.util.Locale

/**
 * Lapis-Pipez：方块式多介质管道附属。
 *
 * 设计要点（完整版见 `docs/conduit-10k-design.md`）：
 * - **管段轻、端点重**：管段无状态、不 tick、不写自定义 PDC；昂贵的东西只挂端点。
 * - **一个管段一个连接节点**，不是六个端口。
 * - **形状由邻居推导**，不持久化。
 * - **批处理**：邻居变化只登记，tick 末统一 flush 一次。
 *
 * 当前进度：M1（管段 + 形状）+ M2（能量网络 + 批处理）。
 * 流体 / 物品端点、贪心合并渲染、升级模块与过滤器是后续里程碑。
 */
class PipezAddon : JavaPlugin(), RebarAddon {

    companion object {
        lateinit var instance: PipezAddon
            private set
    }

    private var flushTask: BukkitTask? = null

    override fun onEnable() {
        instance = this
        // 首次启动把自带的 config.yml（含中英注释）写到插件目录，之后读服务器上的那份
        saveDefaultConfig()
        reloadConfig()

        registerWithRebar()

        initializeBridges()

        PipezItems.initialize()
        PipezBlocks.initialize()
        // ⚠️ 配方必须在**物品 / 方块注册之后**再加 —— 配方里引用的是 Rebar 物品实例。
        PipezRecipes.initialize()
        PipezPages.initialise()

        server.pluginManager.registerEvents(ConduitLinkListener, this)

        // 批处理：所有邻接变化只进队列，这里每个 tick 统一处理一次。
        // 放在 scheduler 上跑（tick 开头）而不是事件回调里跑，是为了把同一 tick 内的
        // 多次改动合并成一次网络重算。
        //
        // ⚠️ 顺序：先 flush（刷新端点登记），再搬物品 —— 反过来的话，
        // 这一 tick 新接上的管子要等下一 tick 才开始搬。
        //
        // ⚠️ 把**真实的调度周期**传给 ItemTransfer：它的"每 N tick 搬一次"是拿
        // 累加的游戏 tick 数取模算的（对标 Pipez 的 gameTime % speed）。
        // 如果 flush 间隔被玩家调成 4，而这里还按"每次调用算一 tick"，
        // 物品搬运会被悄悄拖慢 4 倍（究极档本该每 tick 搬，会变成每 4 tick）。
        val interval = PipezConfig.flushIntervalTicks
        flushTask = server.scheduler.runTaskTimer(
            this,
            Runnable {
                ConduitNetwork.flush()
                ItemTransfer.tick(interval.toInt())
            },
            1L,
            interval
        )
    }

    override fun onDisable() {
        flushTask?.cancel()
        ConduitNetwork.clear()
        ConduitFluid.clear()
        ItemTransfer.clear()
    }

    /**
     * 解析两个反射桥。
     *
     * Rebar 的流体网络（`FluidManager`）和机器虚拟库存（`VirtualInventoryRebarBlock`）
     * 在 Kotlin 层都取不到，只能反射。解析失败**不影响**其它功能，只是关掉对应能力。
     */
    private fun initializeBridges() {
        if (!PipezConfig.useReflection) {
            logger.info("Reflection-based features disabled by config (bridge.use-reflection = false)")
            return
        }

        if (FluidBridge.initialize()) {
            logger.info("Fluid network enabled via reflection on Rebar's internal FluidManager.")
        } else {
            logger.warning("Fluid network unavailable, disabled: ${FluidBridge.failureReason}")
        }

        if (VirtualInventoryBridge.initialize()) {
            logger.info("Rebar machine inventories enabled via reflection.")
        } else {
            logger.warning(
                "Rebar machine inventories unavailable, item pipes will only accept " +
                    "vanilla containers: ${VirtualInventoryBridge.failureReason}"
            )
        }

        logger.warning(
            "The above use Rebar's NON-PUBLIC API. Re-verify after every Rebar update, " +
                "or set bridge.use-reflection = false in config.yml."
        )
    }

    override val javaPlugin: JavaPlugin
        get() = this

    /**
     * Rebar 按 key 的 namespace 匹配附属，默认实现会从插件名推导（"lapis-pipez"），
     * 所以这里显式指定成内容键用的 namespace。
     */
    override fun getKey(): NamespacedKey = PipezKeys.key(PipezKeys.NAMESPACE)

    override val defaultLanguage: Locale
        get() = Locale.ENGLISH

    override val material: Material
        get() = Material.GRAY_CONCRETE
}

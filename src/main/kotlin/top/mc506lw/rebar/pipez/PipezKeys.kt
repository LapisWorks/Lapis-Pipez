package top.mc506lw.rebar.pipez

import org.bukkit.NamespacedKey
import top.mc506lw.rebar.pipez.conduit.ConduitTier
import top.mc506lw.rebar.pipez.conduit.PipeType

/**
 * 所有 NamespacedKey 集中在这里。
 *
 * 内容命名空间 `pipez` 必须和 `PipezAddon.getKey()` 一致。
 * **键一旦发布就不要改名**，否则玩家已有的物品 / 方块会失效。
 */
object PipezKeys {

    const val NAMESPACE = "pipez"

    fun key(name: String): NamespacedKey = NamespacedKey(NAMESPACE, name)

    /**
     * 旧版"全能管"的遗留键。
     *
     * 0.1.x 早期只有这一种管（既传电又传流体）。为了不让老存档里的管子变成空气，
     * 注册时**仍然保留**它；加载后由 `ConduitBlock` 自动迁移成能量管
     * （见 `PipeTypeMigration`）。
     *
     * ⚠️ 不要删。删了老存档里已放置的 `pipez:conduit` 方块会直接失效。
     */
    val CONDUIT = key("conduit")

    /**
     * 某种类型管道的方块 / 物品键，形如 `pipe_energy` / `pipe_fluid`。
     *
     * 每种类型的管道是**独立的方块与物品**（不是同一根管换色），所以键必须分开。
     */
    fun pipe(type: PipeType): NamespacedKey = key(PipeType.ITEM_PREFIX + type.id)

    /** 扳手。 */
    val WRENCH = key("wrench")

    /**
     * 管段方块上"被禁用方向"的 6 bit 掩码（只在非零时写入）。
     *
     * ⚠️ **已降级为兼容键**：0.1.35 起改成 [FACE_MODES]（每面无/抽取/接收三态）。
     * 这个键仍然写（给回滚旧版本的玩家一个降级通道），读的时候只在
     * [FACE_MODES] 缺失（老存档）时才用。**不要删。**
     */
    val DISABLED_FACES = key("disabled_faces")

    /**
     * 管段方块上**每面模式**表（只在非默认时写入）。
     *
     * 编码：6 字符字符串，顺序 = `CARDINAL_FACES`，字符值 = `FaceMode` 序号。
     * 例：`"222222"` = 六面全 INSERT（默认，实际不会写入）；`"021222"` = UP=NONE。
     */
    val FACE_MODES = key("face_modes")

    /**
     * 管段方块上的档位序号（只在非 BASIC 时写入）。
     *
     * ⚠️ **已降级为兼容键**：0.1.38 起档位改成**每面一个升级模块**
     * （见 [SIDE_UPGRADES]），这个键只写"所有面里最高的那一档"，
     * 给回滚旧版本的玩家一个降级通道。**不要删。**
     */
    val TIER = key("tier")

    /**
     * 每面插着的升级模块物品（只在非空时写入）。
     *
     * 编码：`"FACE=BASE64;FACE=BASE64"`，物品用 `ItemStack.serializeAsBytes` +
     * Base64 —— 不依赖任何非公开 API。
     */
    val SIDE_UPGRADES = key("side_upgrades")

    /** 每面的红石模式（6 字符序号表，只在非全默认时写入）。 */
    val SIDE_REDSTONE = key("side_redstone")

    /** 每面的分配模式（6 字符序号表，只在非全默认时写入）。 */
    val SIDE_DISTRIBUTION = key("side_distribution")

    /** 每面的过滤模式（6 字符序号表，只在非全默认时写入）。 */
    val SIDE_FILTER_MODE = key("side_filter_mode")

    /**
     * 每面的过滤器列表（只在非空时写入）。
     *
     * 编码：`"FACE=b64,b64;FACE=b64"`，每个物品用 `ItemStack.serializeAsBytes` + Base64。
     */
    val SIDE_FILTERS = key("side_filters")

    /** 升级模块，键形如 `upgrade_basic`。 */
    fun upgrade(tier: ConduitTier): NamespacedKey = key(ConduitTier.ITEM_PREFIX + tier.id)
}

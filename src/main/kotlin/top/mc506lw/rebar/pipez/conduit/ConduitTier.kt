package top.mc506lw.rebar.pipez.conduit

/**
 * 管段档位。
 *
 * 抄 Pipez 的取舍：**升级存在管子上**（"Every configuration you change and every filter
 * you add is stored in the upgrade"），而不是另做一个端点方块。
 * 所以档位是管段自己的属性，用升级模块右键管段即可提升。
 *
 * 档位决定这条管段参与形成的**每条边**的传输上限。
 */
enum class ConduitTier(
    val id: String,
    val baseCapacity: Double,
    /**
     * 这一档能不能改**红石模式**。对标 Pipez `Upgrade.canChangeRedstoneMode`。
     *
     * 注意 Pipez 的语义：**没插模块（upgrade == null）时三个都是 false** ——
     * 也就是说"不装升级就什么都配不了"。见 `ConduitBlock.upgradeTierOrNull`。
     */
    val canChangeRedstone: Boolean,
    /** 能不能改**分配模式**。对标 `Upgrade.canChangeDistributionMode`。 */
    val canChangeDistribution: Boolean,
    /** 能不能改**过滤模式 / 过滤器列表**。对标 `Upgrade.canChangeFilter`。 */
    val canChangeFilter: Boolean
) {
    /**
     * 每档的**过流上限**（瓦特）—— 只有在 `conduit.tier-power-limit: true` 时才会写进
     * `Edge.powerLimit`。**默认不限流，所以这个值默认不生效。**
     *
     * ## ⚠️ 为什么默认不限流
     *
     * Rebar 的 `ElectricConsumerNode` **没有能量缓冲**：每 tick 报一个 `requiredPower`，
     * 网络必须当 tick 给满，否则 `isPowered` 为 false、机器**完全不工作**（不是变慢）。
     * `calculateLoadOnEdges` 用 `powerLimit - 已用` 卡过流量，给不满就是给不满。
     *
     * 所以任何低于机器需求的上限都等于"把机器关掉"。实测（铁熔炉）：
     * - 铜熔炉 `4000/180 × 2 槽 ≈ 44 W` —— 1024 都够
     * - 下界合金熔炉 `4000/5 × 6 槽 = 4800~19200 W` —— 1024 直接卡死
     *
     * 隔壁 `Lapis-Cable` 完全不设上限（它的"容量"只用于 tooltip），所以它能带动
     * 我们带不动的机器。
     *
     * ## 数值：和 `Lapis-Cable` 统一（取它的"规格"档 1x/2x/4x/9x）
     *
     * 电缆的下界合金基础是 8192 W，规格是 1x/2x/4x/9x/16x。
     * 这里取 **1x/2x/4x/9x** ⇒ 8192 / 16384 / 32768 / **73728**：
     * - 每档 ×2，曲线平滑（不取 16x 是因为那一步是 ×4，膨胀太快）；
     * - 究极档 73728 带得动**满速下界合金熔炉**（6 槽 × 3200 W = 19200 W）还有近 4 倍余量。
     *
     * 这样两个附属的"能带多大功率"是同一把尺子，玩家不用在两套数值之间换算。
     */
    //                                                          红石   分配   过滤
    // 容量与 `Lapis-Cable` **统一**：照它的"下界合金"那一列、取它的**规格**档位
    // 1x / 2x / 4x / 9x（下界合金基础 8192 W）⇒ 8192 / 16384 / 32768 / 73728。
    //
    // ⚠️ 不取 16x（131072）：那一步是 ×4 的跳变，相对上一档膨胀太快。
    // 取 9x 之后每档 ×2，曲线平滑；而且 **73728 已经远超"满速下界合金熔炉"需要的
    // 19200 W**（6 槽 × 3200 W），究极档带得动它还有 3.8 倍余量。
    BASIC("basic", 8_192.0, true, false, false),
    IMPROVED("improved", 16_384.0, true, true, false),
    ADVANCED("advanced", 32_768.0, true, true, true),
    ULTIMATE("ultimate", 73_728.0, true, true, true);

    /** 下一档；已经是最高档返回 null。 */
    val next: ConduitTier? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** 升级物品的键是 `upgrade_<id>`，从键反解档位。 */
        const val ITEM_PREFIX = "upgrade_"

        fun fromItemKey(key: String): ConduitTier? =
            key.removePrefix(ITEM_PREFIX).takeIf { it != key }?.let(::fromId)

        fun fromId(id: String): ConduitTier? = entries.firstOrNull { it.id == id }

        /** 从 PDC 里的序号还原；越界回退到基础档。 */
        fun fromOrdinal(value: Int): ConduitTier = entries.getOrNull(value) ?: BASIC
    }
}

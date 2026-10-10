package top.mc506lw.rebar.pipez.conduit

/**
 * 管道**每个面**的三组设置 —— 逐条对标 Pipez 的 `UpgradeTileEntity` 内嵌枚举。
 *
 * Pipez 的配置界面（`ExtractScreen`）在每个面上都有这三个循环按钮，
 * 枚举值和默认值**完全照抄**（见 `UpgradeTileEntity.RedstoneMode` /
 * `Distribution` / `FilterMode`，以及 `PipeType.getDefault*`）。
 *
 * ⚠️ **序号（ordinal）一旦发布不要改**：PDC 里存的是序号。
 * 加新值只能往后追加。
 */

/**
 * 红石模式 —— 对标 `UpgradeTileEntity.RedstoneMode`。
 *
 * 默认 [IGNORED]（`PipeType.getDefaultRedstoneMode`）。
 * 这个模式是**真的生效**的：见 `ConduitBlock.connectsTo` ——
 * 不满足条件的面直接不连。
 */
enum class RedstoneMode(val id: String) {
    /** 忽略红石（默认）。 */
    IGNORED("ignored"),

    /** 有红石信号时**停止**工作。 */
    OFF_WHEN_POWERED("off_when_powered"),

    /** 有红石信号时**才**工作。 */
    ON_WHEN_POWERED("on_when_powered"),

    /** 永远不工作。 */
    ALWAYS_OFF("always_off");

    val next: RedstoneMode get() = entries[(ordinal + 1) % entries.size]
    val previous: RedstoneMode get() = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        val DEFAULT = IGNORED
        fun fromOrdinal(value: Int): RedstoneMode = entries.getOrNull(value) ?: DEFAULT
    }
}

/**
 * 分配模式 —— 对标 `UpgradeTileEntity.Distribution`。
 *
 * 默认 [ROUND_ROBIN]（`PipeType.getDefaultDistribution`）。
 *
 * ⚠️ **目前只存不生效**：能量侧的路由由 Rebar 的 `ElectricNetwork` 自己算
 * （`findBestPath` / `distributePowerTo*`），我们插不进去；
 * 流体侧同理走 Rebar 的 `FluidManager`。所以这个值现在只是**存下来**，
 * 等以后自己实现传输时再拿来排序。
 */
enum class Distribution(val id: String) {
    NEAREST("nearest"),
    FURTHEST("furthest"),
    ROUND_ROBIN("round_robin"),
    RANDOM("random");

    val next: Distribution get() = entries[(ordinal + 1) % entries.size]
    val previous: Distribution get() = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        val DEFAULT = ROUND_ROBIN
        fun fromOrdinal(value: Int): Distribution = entries.getOrNull(value) ?: DEFAULT
    }
}

/**
 * 过滤模式 —— 对标 `UpgradeTileEntity.FilterMode`。
 *
 * 默认 [WHITELIST]（`PipeType.getDefaultFilterMode`）。
 *
 * ⚠️ **只有流体管有过滤器** —— Pipez 的 `EnergyPipeType.hasFilter()` 返回 `false`，
 * 能量管压根没有过滤器这一项。所以能量管的界面上不该出现这个按钮。
 */
enum class FilterMode(val id: String) {
    WHITELIST("whitelist"),
    BLACKLIST("blacklist");

    val next: FilterMode get() = entries[(ordinal + 1) % entries.size]
    val previous: FilterMode get() = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        val DEFAULT = WHITELIST
        fun fromOrdinal(value: Int): FilterMode = entries.getOrNull(value) ?: DEFAULT
    }
}

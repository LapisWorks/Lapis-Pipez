package top.mc506lw.rebar.pipez

import top.mc506lw.rebar.pipez.conduit.ConduitTier

/**
 * `config.yml` 的读取层（首次启动生成在插件目录）。
 *
 * 读不到的项回退到代码里的默认值，所以配置文件缺项不会出问题。
 */
object PipezConfig {

    private val config
        get() = PipezAddon.instance.config

    /** 整体倍率：所有档位的容量都乘它。 */
    val capacityMultiplier: Double
        get() = positive("conduit.capacity-multiplier", 1.0)

    /**
     * 是否把档位容量写进 `Edge.powerLimit`（**每 tick 的过流上限**）。**默认 true**。
     *
     * ## 只写在"抽取面"那条边上
     *
     * 对标 Pipez 的 `EnergyPipeType.pullEnergy`：
     * ```java
     * if (!tileEntity.isExtracting(side)) return;              // 只有抽取面动
     * int energyToTransfer = getRate(tileEntity, side);        // 速率 = 【抽取面】的升级
     * ```
     * **速率只属于抽取面**，接收面不参与那次搬运（`insert` 那边没有 rate 限制）。
     * 所以 `refreshElectric` 只给"抽取面 ↔ 机器"那条边写上限，其余边一律不限。
     *
     * ## 为什么"写哪条边"这么关键
     *
     * Rebar 的 `calculateLoadOnEdges` 用 `powerLimit − 已用` 卡每条边，
     * **路径吞吐 = 路径上所有边里最小的那个**。把上限写在接收面上（比如"管道→机器"那条）
     * 就会莫名其妙把整条路径压死 —— 实测教训：究极插在电源面、机器面没插 →
     * 压到基础档 8192 W → 下界合金只带得动 2 组并行（2×3200=6400 ✔ / 3×3200=9600 ✘）。
     *
     * ## ⚠️ 代价（必须知道）
     *
     * Rebar 的 `ElectricConsumerNode` **没有能量缓冲**：它每 tick 报一个 `requiredPower`，
     * 网络必须当 tick 给满，否则 `isPowered` 为 false、机器**完全不工作** ——
     * 不是"供得慢一点"，是直接停摆。
     *
     * 所以**抽取面的档位低于机器每 tick 需求时，机器就是不干活**。
     * 遇到"电在掉、机器不干活"，先看界面里的「本面升级 / 本面上限」。
     *
     * 想彻底不限流（像 Lapis-Cable 那样）就把 `conduit.tier-power-limit` 设成 `false`，
     * 再用 [capacityMultiplier] 整体调大小。
     */
    val tierPowerLimit: Boolean
        get() = config.getBoolean("conduit.tier-power-limit", true)

    /**
     * 某一档的传输上限（瓦特）= 该档基础容量 × 整体倍率。
     *
     * ⚠️ 基础容量**只认 [ConduitTier.baseCapacity]（代码里的值）**，
     * 不再从 `config.yml` 的 `tiers.*` 读 —— 原因：配置文件是**生成一次就不再更新**的，
     * 一旦某版把它写进旧的低值（曾经写过 1024），插件升级后仍然会被旧配置覆盖，
     * 于是"改了代码却没生效"。要调就用 [capacityMultiplier]（倍率，旧配置里的 1.0 无害）。
     *
     * ⚠️ 这个值会写到 `Edge.powerLimit` 上（Rebar 默认是 `Double.MAX_VALUE` = 不限）。
     * **调小它会真的卡住下游机器**：Rebar 的 consumer 没有能量缓冲，每 tick 必须给满
     * `requiredPower`，给不满就完全不工作（用户实测：电在掉、熔炉不烧）。
     */
    fun capacity(tier: ConduitTier): Double = tier.baseCapacity * capacityMultiplier

    /** 批处理 flush 的间隔（tick）。1 = 每 tick 一次。 */
    val flushIntervalTicks: Long
        get() = config.getLong("conduit.flush-interval-ticks", 1L).coerceAtLeast(1L)

    // ---------------------------------------------------------------- 管道造型

    // ⚠️ 管身 / 彩条的颜色**不在这里配** —— 它们是管道类型的身份，
    // 固定在 `PipeType` 里（能量=红、流体=蓝）。
    // 想让服主改色就改 `conduit/PipeType.kt`。这里只放与颜色无关的几何参数。

    /** 管身粗细（格）。 */
    val bodyThickness: Float
        get() = config.getDouble("render.body-thickness", 0.32).toFloat().coerceIn(0.05f, 0.9f)

    /** 彩条粗细。默认约为管身的 1/3。 */
    val stripeThickness: Float
        get() = config.getDouble("render.stripe-thickness", 0.11).toFloat().coerceIn(0.02f, 0.9f)

    /** 彩条比管身表面再凸出多少。太小会看不出彩条、还容易和管身抢面。 */
    val stripeProtrusion: Double
        get() = config.getDouble("render.stripe-protrusion", 0.03)

    /**
     * 彩条在**接头那一端**伸到"正交臂彩条外轮廓"之上再多留的余量。
     *
     * 基础量是 `stripeFullWidth/2`（正交臂含彩条的整体半宽），这个值只是保险余量。
     * 调大 → 彩条头伸得更深（但也更凸出）；调 0 → 刚好停在对方轮廓上。
     */
    val stripeIntoJoint: Double
        get() = config.getDouble("render.stripe-into-joint", 0.0).coerceIn(0.0, 0.1)

    // ---------------------------------------------------------------- 「心」的造型

    // ⚠️ 这里**故意没有**「拐弯 / 接头中心方块」这个配置项（旧的 `render.junction-cube-size` 已删）。
    //
    // 拐弯 / 接头的中心现在画的是一个**「心」**（`ConduitBlock.spawnHeart`），
    // 尺寸固定 = [bodyThickness]，和直管每格中心那个心完全一致。
    //
    // 为什么删：以前拐角中心是个**光秃秃的方块**（`spawnHub`，没有箍），而直管每格中心
    // 都画「心」（方块 + 三个箍）。于是"4 格围成一圈"（全是拐角）一个心都看不到，
    // 而 L 形直管上每格都有心 —— 用户截图："他们完全不一样对吧？这就是问题"。
    //
    // ⚠️ 同样**故意没有**「心 的尺寸」配置项。
    //
    // 心（= 0 通造型）的粗细**必须等于管身**（[bodyThickness]），箍的宽度必须等于
    // 管身彩条的全宽（[stripeFullWidth]）—— 这是原版 Pipez 的做法
    // （`SHAPE_CORE` 和 `SHAPE_EAST` 都是 6/16 方块，core 与臂同粗）。
    //
    // 之前这里有过 `isolated-cube-size`（默认 0.35）和 `ringSize`，比管身（0.32）
    // 粗 0.03 → 每个心都从管身上鼓出一小圈（用户实测："为啥心比臂粗一点啊"）。
    // 两个尺寸能分别配 = 它们必然会对不上，所以直接删掉，全部由 [bodyThickness] 推导。

    /**
     * 彩条的**横截面全宽** = 管身 + 两侧各凸出一点。
     *
     * 因为彩条是"一个方向全宽、另一个方向很薄"的长方体，所以它会在管身的**两个相对侧面**上露出来。
     * 两条互相垂直的彩条 → 四个侧面都有彩条（横截面上是个"＋"）。
     *
     * 「心」的三个箍也用这个宽度 —— 保证心的箍和管身的彩条一样粗。
     */
    val stripeFullWidth: Float
        get() = (bodyThickness + stripeProtrusion * 2).toFloat()

    /** 「心」三个箍沿管轴方向的长度（要短，才像一圈箍而不是一块板）。 */
    val bandLength: Double
        get() = config.getDouble("render.band-length", 0.08).coerceAtLeast(0.01)

    /**
     * 是否画两条彩条（管道的"＋"形截面身份色）。**默认开**。
     *
     * ⚠️ 这是**纯装饰**，但它占每臂实体数的 2/3（每臂 = 管身 + 2 彩条）。
     * 关掉之后实体数直接降到 1/3 —— 锯齿状大管网（拐弯多、贪心合并几乎不生效）
     * 卡 TPS 时，这是最有效的一刀。
     */
    val renderStripes: Boolean
        get() = config.getBoolean("render.stripes", true)

    /**
     * 是否画「心」的三个箍（每个心一圈）。**默认开**。
     *
     * ⚠️ 纯装饰 —— 心的**方块**本体永远画（它是管子的实心核心），只有这三个箍受本开关控制。
     * 但它们是**每格三个**，所以在大型管网里是数量最多的一类实体。
     */
    val renderBands: Boolean
        get() = config.getBoolean("render.bands", true)

    /**
     * ⚠️ **物品搬运速度/个数不在这里** —— 它们按**抽取面的档位**查表，
     * 写死在 [ConduitTier.itemSpeed] / [ConduitTier.itemAmount]（逐格照抄 Pipez
     * `ServerConfig` 的 `item_pipe.speed.*` 与 `item_pipe.amount.*` 默认值）。
     *
     * 原因和档位容量一样：配置文件**生成一次就不再更新**，一旦某版把旧值写进去，
     * 插件升级后仍会被旧配置覆盖 → "改了代码却没生效"。
     * 要整体调，请改 [ConduitTier] 里那两个字段。
     *
     * （历史：0.1.58 之前这里有个 `cargo.items-per-tick`，是"所有档位一个速度"的
     * 临时方案 —— 已删除，因为它和"升级模块真的提速"这条 Pipez 规则冲突。）
     */

    /** 是否启用基于反射的功能（流体网络 + Rebar 机器虚拟库存）。 */
    val useReflection: Boolean
        get() = config.getBoolean("bridge.use-reflection", true)

    /** 流体网络的传输速率（mB/s）。 */
    val fluidPerSecond: Double
        get() = positive("fluid.per-second", 1_000.0)

    private fun positive(path: String, fallback: Double): Double =
        config.getDouble(path, fallback).takeIf { it.isFinite() && it > 0.0 } ?: fallback
}

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
     * 是否把档位容量写进 `Edge.powerLimit`（**每 tick 的过流上限**）。
     *
     * ⚠️ **默认 false（不限流）** —— 这是刻意的，也是唯一安全的默认值：
     *
     * - Rebar 的 `ElectricConsumerNode` **没有能量缓冲**：它每 tick 报一个 `requiredPower`，
     *   网络必须当 tick 给满，否则 `isPowered` 为 false、机器**完全不工作**。
     *   `calculateLoadOnEdges` 用 `powerLimit - 已用` 卡过流量，给不满就是给不满。
     * - 所以上限**低于机器需求时会直接卡死机器**，而不是"供得慢一点"。
     * - 隔壁 `Lapis-Cable` 就完全不设上限（它的"容量"只用来显示 tooltip），所以它能带动
     *   我们的管子带不动的机器 —— 用户实测。
     *
     * 想要 Pipez 式的限流就把它设成 `true`，再用 [capacityMultiplier] 调大小。
     */
    val tierPowerLimit: Boolean
        get() = config.getBoolean("conduit.tier-power-limit", false)

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
     * 彩条在**尽头那一端**多伸出去多少。
     *
     * 两个作用：
     * ① 一眼能看出"这里到端了"（1 通和 2 通就区分开了）；
     * ② 彩条端面不会和管身端面共面 → 不会 Z-fighting。
     */
    val stripeEndOverhang: Double
        get() = config.getDouble("render.stripe-end-overhang", 0.03).coerceAtLeast(0.0)

    /**
     * 彩条在**接头那一端**伸到"正交臂彩条外轮廓"之上再多留的余量。
     *
     * 基础量是 `stripeFullWidth/2`（正交臂含彩条的整体半宽），这个值只是保险余量。
     * 调大 → 彩条头伸得更深（但也更凸出）；调 0 → 刚好停在对方轮廓上。
     */
    val stripeIntoJoint: Double
        get() = config.getDouble("render.stripe-into-joint", 0.0).coerceIn(0.0, 0.1)

    /**
     * 拐弯 / 接头中心那个方块（用来填两臂之间的凹角）的边长。
     *
     * ⚠️ 默认 `<= 0` 表示**跟随管身粗细**（[bodyThickness]）—— 这是对的：
     * 原版 Pipez 的拐角管身不会变粗。只有显式配成正数时才放大。
     */
    val junctionCubeSize: Float
        get() {
            val raw = config.getDouble("render.junction-cube-size", -1.0).toFloat()
            return if (raw <= 0f) bodyThickness else raw.coerceIn(0.1f, 1.0f)
        }

    // ---------------------------------------------------------------- 0 通的造型

    /**
     * 0 通时那个黑色正方体的边长。
     *
     * 默认 0.35 —— 和 Pylon `CargoDuct.createNotConnectedDuctDisplay` 的
     * `TransformBuilder().scale(0.3495)` 一个量级，就是"没接上的管道头"那么大。
     */
    val isolatedCubeSize: Float
        get() = config.getDouble("render.isolated-cube-size", 0.35).toFloat().coerceIn(0.05f, 1.0f)

    /** 0 通时三个黄箍的截面尺寸：比正方体大一点点。 */
    val ringSize: Float
        get() = (isolatedCubeSize + stripeProtrusion * 2).toFloat()

    /**
     * 彩条的**横截面全宽** = 管身 + 两侧各凸出一点。
     *
     * 因为彩条是"一个方向全宽、另一个方向很薄"的长方体，所以它会在管身的**两个相对侧面**上露出来。
     * 两条互相垂直的彩条 → 四个侧面都有彩条（横截面上是个"＋"）。
     */
    val stripeFullWidth: Float
        get() = (bodyThickness + stripeProtrusion * 2).toFloat()

    /**
     * 彩箍粗细。
     *
     * 两个约束：
     * ① 必须**明显不同于** [stripeFullWidth]，否则两个面完全重合，箍看起来是个空框；
     * ② 必须**只比管身粗一点点**，太粗会像多出来的一对横向臂（用户截图里红框标的就是这个）。
     */
    val bandThickness: Float
        get() = config.getDouble("render.band-thickness", 0.34).toFloat().coerceIn(0.05f, 0.95f)

    /** 彩箍沿管轴方向的长度（要短，才像一圈箍而不是一块板）。 */
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
     * 是否画彩箍（每格一圈的细环）。**默认开**。
     *
     * ⚠️ 同样是纯装饰，但**每格/每臂一个**，所以在大型管网里是数量最多的一类实体。
     */
    val renderBands: Boolean
        get() = config.getBoolean("render.bands", true)

    /** 物品端点每 tick 最多搬运几个物品。 */
    val itemsPerTick: Int
        get() = config.getInt("cargo.items-per-tick", 8).coerceAtLeast(1)

    /** 是否启用基于反射的功能（流体网络 + Rebar 机器虚拟库存）。 */
    val useReflection: Boolean
        get() = config.getBoolean("bridge.use-reflection", true)

    /** 流体网络的传输速率（mB/s）。 */
    val fluidPerSecond: Double
        get() = positive("fluid.per-second", 1_000.0)

    private fun positive(path: String, fallback: Double): Double =
        config.getDouble(path, fallback).takeIf { it.isFinite() && it > 0.0 } ?: fallback
}

package top.mc506lw.rebar.pipez.conduit

/**
 * 管道类型 —— 一根管道"走什么介质"的身份。
 *
 * ## 为什么要单独抽一层
 *
 * 0.1.x 早期只有一根"全能管"：同一个 `ConduitBlock` 既挂电力节点（`ElectricRebarBlock`）
 * 又挂流体点（[top.mc506lw.rebar.pipez.fluid.ConduitFluid]）——
 * 一根管同时传电和传流体，没有任何区分。要做成 Pipez 那样"能量管 / 流体管 / 物品管"
 * 各自独立、各传各的，就必须先把"类型"这个概念从方块里拆出来。
 *
 * 拆出来之后，[PipeType] 就是这个概念的唯一真相来源：
 * - **颜色/材质**：能量管红、流体管蓝、物品管橙（颜色一眼分得清走的是什么）；
 * - **网络类别**（[category]）：决定这根管接哪一套网络逻辑；
 * - **物品/方块键**：每种类型一个内容键，玩家背包里也是几种独立物品。
 *
 * ## 不互通是硬规则
 *
 * [ConduitBlock.connectsTo] 只在**同类型**之间成立 —— 能量管和流体管贴在一起也不会连。
 * 这是有意的：几种网络的语义完全不同（一个限流瓦特、一个限流速 mB/s、一个搬物品），
 * 混在一根管里既没法配也没法调。想要"同时走电和流体"就并排铺两根。
 */
enum class PipeType(
    /** 内容键后缀，例如 `pipe_energy`。一旦发布不要改。 */
    val id: String,

    /** 这个类型的管道属于哪一套网络。 */
    val category: Category,

    /** 管身材质。 */
    val bodyMaterial: org.bukkit.Material,

    /** 彩条 / 彩箍材质（表示走什么介质）。 */
    val stripeMaterial: org.bukkit.Material
) {

    /** 能量管道：红色彩条。 */
    ENERGY(
        id = "energy",
        category = Category.ENERGY,
        bodyMaterial = org.bukkit.Material.BLACK_CONCRETE,
        stripeMaterial = org.bukkit.Material.RED_CONCRETE
    ),

    /** 流体管道：蓝色彩条。 */
    FLUID(
        id = "fluid",
        category = Category.FLUID,
        bodyMaterial = org.bukkit.Material.BLACK_CONCRETE,
        stripeMaterial = org.bukkit.Material.LIGHT_BLUE_CONCRETE
    ),

    /** 物品管道：橙色彩条。 */
    ITEM(
        id = "item",
        category = Category.ITEM,
        bodyMaterial = org.bukkit.Material.BLACK_CONCRETE,
        stripeMaterial = org.bukkit.Material.ORANGE_CONCRETE
    );

    /**
     * 网络类别。决定一个管段要接哪套网络逻辑：
     * - [ENERGY] → 建电力节点、写边限流（走 Rebar 的 `ElectricNetwork`）
     * - [FLUID] → 建流体点、连流体段（走 [top.mc506lw.rebar.pipez.fluid.ConduitFluid]）
     * - [ITEM] → 物品搬运（见 [PipeType.ITEM]）。管段本身**不 tick**，
     *   实际搬运挂在 [ConduitBlock] 的中央物品任务上：哪根管贴着容器，
     *   那一面就是一个端点。
     *
     * 以后加"气体"也只需要在这里加一项。
     */
    enum class Category {
        ENERGY,
        FLUID,
        ITEM
    }

    companion object {
        /** 物品键形如 `pipe_energy`，从键反解类型。 */
        const val ITEM_PREFIX = "pipe_"

        fun fromItemKey(key: String): PipeType? =
            key.removePrefix(ITEM_PREFIX).takeIf { it != key }?.let(::fromId)

        fun fromId(id: String): PipeType? = entries.firstOrNull { it.id == id }
    }
}

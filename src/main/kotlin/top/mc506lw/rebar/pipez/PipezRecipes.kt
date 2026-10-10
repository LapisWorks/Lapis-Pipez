package top.mc506lw.rebar.pipez

import io.github.pylonmc.rebar.recipe.RecipeType
import io.github.pylonmc.rebar.recipe.vanilla.ShapedRebarRecipe
import io.github.pylonmc.rebar.recipe.vanilla.ShapelessRebarRecipe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.recipe.CraftingBookCategory
import top.mc506lw.rebar.pipez.conduit.ConduitTier
import top.mc506lw.rebar.pipez.conduit.PipeType

/**
 * 管道合成。
 *
 * ## 定价基准：照 `Lapis-Cable` 的"每瓦成本"
 *
 * 电缆的尺子很好算：
 * - 底料：**8 个原料摆一圈 → 8 根 1x**，也就是 **1 个原料 = 1 根线**；
 * - 拧线：`1 根 xN = N 个原料`（粗线只省根数、不省料）；
 * - 于是 **铜 1x = 1 铜锭 = 512 W**，**下界合金 1x = 1 下界合金锭 = 8192 W**。
 *
 * 管道这边一律照"每瓦成本"对齐（详见 [registerPipes] / [registerUpgrades] 的推导）：
 *
 * ## 完整配方表
 *
 * | 产物 | 图案 | 数量 | 折合 |
 * |---|---|---|---|
 * | 能量管道 | 8 铜锭围 1 红石 | **1** | 8 铜锭/根 → 约 1024 W/铜锭 |
 * | 流体管道 | 8 铜锭围 1 玻璃 | **1** | 同上 |
 * | 物品端点 | 8 铁锭围 1 漏斗 | 1 | — |
 * | 扳手 | 铜 / 铁 / 铜 竖排 | 1 | — |
 * | 基础升级 | 1 个下界合金锭 | 1 | 8192 W |
 * | 强化升级 | 2 个下界合金锭 | 1 | 16384 W |
 * | 高级升级 | 4 个下界合金锭 | 1 | 32768 W |
 * | 究极升级 | 9 个下界合金锭 | 1 | 73728 W |
 *
 * ## 为什么升级模块这么贵
 *
 * 两点原因：
 *
 * 1. **升级模块才是"档位"**（管道本体只有能量 / 流体两种，档位靠插模块）。
 *    而档位现在是**解锁界面功能的钥匙**（红石=基础 / 分配=强化 / 过滤=高级），
 *    所以它的价格应该明显高于管道本身 —— 玩家得"先铺管、再投资升级"。
 * 2. **价格必须和容量成正比**，否则就是在"偷" —— 见 [registerUpgrades] 的定价表：
 *    按 `Lapis-Cable` 的"1 个下界合金锭 = 8192 W"折算，四个档位分别是 1/2/4/9 个锭。
 *
 * ## 想调贵/调便宜
 *
 * 全在这一个文件里：
 * - 想让管道更便宜 → 把 [registerPipes] 的 `amount` 从 1 往上调（2 = 4 铜锭/根）；
 * - 想让升级更贵 / 更便宜 → 改 [registerUpgrades] 里那张 `tier to 锭数` 表
 *   （想整体翻倍就全 ×2，想换材料就把 `NETHERITE_INGOT` 换掉）。
 *
 * 配方键统一 `craft_` 前缀，和物品键区分开。
 */
object PipezRecipes {

    fun initialize() {
        registerPipes()
        registerEndpoint()
        registerWrench()
        registerUpgrades()
    }

    /**
     * 管道本体：8 铜锭围一圈，中心决定类型（红石=能量 / 玻璃=流体）。**产 1 根**。
     *
     * ## 为什么是"产 1 根"而不是"产 8 根"
     *
     * 电缆的底料是 `8 原料 → 8 根`，即 **1 铜锭 = 1 根铜 1x = 512 W**。
     * 而管道本体在"基础档"就已经是 **8192 W**（见 [ConduitTier]）。
     *
     * 如果照抄"8 → 8"，一根管道只要 1 铜锭就给 8192 W —— **每铜锭 8192 W，
     * 是电缆的 16 倍**，等于白送。
     *
     * 改成 `8 原料 → 1 根`（= 8 铜锭/根）之后：
     * **8192 W ÷ 8 铜锭 ≈ 1024 W/铜锭**，和电缆的 512 W/铜锭同一量级（只快 2 倍，
     * 算是管道相对线缆的"体积优势"）。
     *
     * ⚠️ 想要**完全**对齐电缆，就再翻一倍（16 铜锭/根，把产出改成"8 铜锭 → 0.5 根"
     * 做不到，所以要么加中心材料要么接受这 2 倍）。
     */
    private fun registerPipes() {
        ring(
            key = "pipe_energy",
            ringMaterial = Material.COPPER_INGOT,
            center = Material.REDSTONE,
            result = PipezBlocks.conduitItemFor(PipeType.ENERGY),
            amount = 1
        )
        ring(
            key = "pipe_fluid",
            ringMaterial = Material.COPPER_INGOT,
            center = Material.GLASS,
            result = PipezBlocks.conduitItemFor(PipeType.FLUID),
            amount = 1
        )
    }

    /** 物品端点：8 铁锭围 1 个漏斗。 */
    private fun registerEndpoint() {
        ring(
            key = "item_endpoint",
            ringMaterial = Material.IRON_INGOT,
            center = Material.HOPPER,
            result = PipezBlocks.ITEM_ENDPOINT_ITEM,
            amount = 1
        )
    }

    /** 扳手：铜 / 铁 / 铜 竖排。 */
    private fun registerWrench() {
        val recipe = ShapedRecipe(key("wrench"), PipezItems.WRENCH.clone())
        recipe.shape(" C ", " I ", " C ")
        recipe.setIngredient('C', Material.COPPER_INGOT)
        recipe.setIngredient('I', Material.IRON_INGOT)
        register(recipe)
    }

    /**
     * 升级模块：**价格 = 容量**，用电缆那把尺子定价。
     *
     * ## 为什么是"N 个下界合金锭"
     *
     * `Lapis-Cable` 的定价是 **1 个下界合金锭 = 8192 W**（下界合金基础容量 8192，
     * 规格 1x/2x/4x/9x/16x，所以 16x = 16 个下界合金锭 = 131072 W）。
     *
     * 我们的档位容量正好是照它的 **1x/2x/4x/9x** 定的，所以价格也应该一一对应：
     *
     * | 档位 | 容量 | 对应电缆 | 材料 |
     * |---|---|---|---|
     * | 基础 | 8,192 W | 下界合金 1x | **1** 个下界合金锭 |
     * | 强化 | 16,384 W | 下界合金 2x | **2** 个 |
     * | 高级 | 32,768 W | 下界合金 4x | **4** 个 |
     * | 究极 | 73,728 W | 下界合金 9x | **9** 个 |
     *
     * ⚠️ 之前写的是"8 钻石 + 1 下界合金锭"，按这把尺子只值 ~1 个下界合金锭的价钱
     * 却给 73728 W —— **比电缆便宜约 9 倍**。现在对齐。
     *
     * 用**无序配方**（而不是 8 个围一圈）是因为数量 1/2/4/9 摆不进九宫格图案；
     * 电缆的"拧线"那几条也是无序的，风格一致。
     */
    private fun registerUpgrades() {
        // 容量 ÷ 8192 = 该付几个下界合金锭（正好是电缆的规格档 1x/2x/4x/9x）
        val table = listOf(
            ConduitTier.BASIC to 1,
            ConduitTier.IMPROVED to 2,
            ConduitTier.ADVANCED to 4,
            ConduitTier.ULTIMATE to 9
        )
        for ((tier, ingots) in table) {
            val recipe = ShapelessRecipe(key("upgrade_${tier.id}"), PipezItems.UPGRADES.getValue(tier).clone())
            recipe.addIngredient(ingots, Material.NETHERITE_INGOT)
            registerShapeless(recipe)
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 通用"8 个原料围一圈 + 中心一个"的配方。 */
    private fun ring(
        key: String,
        ringMaterial: Material,
        center: Material,
        result: ItemStack,
        amount: Int
    ) {
        val recipe = ShapedRecipe(key(key), result.clone().apply { this.amount = amount })
        recipe.shape("RRR", "RCR", "RRR")
        recipe.setIngredient('R', ringMaterial)
        recipe.setIngredient('C', center)
        register(recipe)
    }

    private fun register(recipe: ShapedRecipe) {
        recipe.setCategory(CraftingBookCategory.MISC)
        RecipeType.VANILLA_SHAPED.addRecipe(ShapedRebarRecipe.fromVanilla(recipe))
    }

    private fun registerShapeless(recipe: ShapelessRecipe) {
        recipe.setCategory(CraftingBookCategory.MISC)
        RecipeType.VANILLA_SHAPELESS.addRecipe(ShapelessRebarRecipe.fromVanilla(recipe))
    }

    private fun key(name: String): NamespacedKey = PipezKeys.key("craft_$name")
}

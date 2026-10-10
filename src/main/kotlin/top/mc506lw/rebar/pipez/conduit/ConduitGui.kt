package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.util.gui.unit.UnitFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.items.UpgradeItem
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.UpdateReason
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemWrapper
import xyz.xenondevs.invui.window.Window

/**
 * 管道的**配置界面** —— 复刻 Pipez 的 `ExtractScreen`。
 *
 * ## 打开方式（复刻 Pipez 的触发条件）
 *
 * Pipez 是 `EnergyPipeBlock.onPipeSideActivated`：
 * 右键管道上**处于"抽取"的那一面** → `PipeContainerProvider.openGui(player, pipe, side, index)`。
 *
 * 这里放宽成**任意面**都能开 —— 因为 Pipez 只有"抽取面"能开，
 * 玩家点到普通面时毫无反馈，很容易误以为界面坏了。
 * 开出来的是**你点的那个面**的配置页，所以放宽不丢功能。
 *
 * ⚠️ **潜行右键不开** —— 那个手势留给扳手（`WrenchItem`）。
 *
 * ## 为什么是"每面一页"而不是"六个面挤一页"
 *
 * Pipez 的容器是 `ExtractContainer(pipe, side, index)` —— **每个面各有一个界面**，
 * 打开的界面对应你点的那一面。所以本界面也只配 [face] 这一个面。
 *
 * ## 界面内容（对齐 Pipez 的 `ExtractScreen`）
 *
 * | 位置 | 内容 | 对标 Pipez |
 * |---|---|---|
 * | 第一行左 | 红石模式循环按钮 | `redstoneButton` |
 * | 第一行中 | 分配模式循环按钮 | `sortButton` |
 * | 第一行右 | 过滤模式循环按钮 | `filterButton` |
 * | 第 2~3 行 | 过滤器列表（18 格） | `FilterList` |
 * | 第 4 行 | 升级槽 + 添加 / 删除 / 清空 | `UpgradeSlot` + 三个按钮 |
 * | 第 5 行 | 用法说明 | — |
 *
 * ## ⚠️ 哪些"真的生效"、哪些"只存不生效"
 *
 * - **红石模式**：**真的生效**。`ConduitBlock.connectsTo` 会拿 `redstoneAllows` 把关。
 * - **升级槽**：**真的生效**。档位决定能量边的瓦特上限（`applyEdgeLimit`）。
 * - **分配模式 / 过滤模式 / 过滤器列表**：**只存不生效**。
 *   能量侧的路由是 Rebar 的 `ElectricNetwork` 自己算的（`findBestPath`），
 *   流体侧走 Rebar 的 `FluidManager`，我们插不进去。等以后自己实现传输时再接。
 */
object ConduitGui {

    // ---------------------------------------------------------------- 布局

    private const val BG = '.'
    private const val MODE = 'm'
    private const val REDSTONE = 'r'
    private const val DISTRIBUTION = 'd'
    private const val FILTER_MODE = 'f'
    private const val UPGRADE = 'u'
    private const val ADD = 'a'
    private const val REMOVE = 'b'
    private const val CLEAR = 'c'
    private const val HELP = 'h'

    /** 18 个过滤器格子的字符（不与上面任何一个撞）。 */
    private val FILTER_CHARS: List<Char> = "0123456789ABCDEFGH".toList()

    /**
     * "这个设置改不了"时统一的说明 —— **红字**，一字不差照用户要求写。
     *
     * 用在：能量 / 流体的**分配模式**、能量管的**过滤模式 + 过滤器列表**。
     * 图标一律是**屏障**（`Material.BARRIER`）。
     */
    private val LOCKED_LORE: List<Component> = listOf(
        Component.text("受制于底层逻辑，无法更改", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false)
    )

    /**
     * "要先装升级模块"的说明 —— 对标 Pipez：**没插模块时三个模式按钮全部不可用**
     * （`Upgrade.canChange*(null)` 全是 false）。
     *
     * 这也是**升级模块真正的用处**：它不是"提升速度"（Rebar 的 consumer 没有缓冲，
     * 限流只会把机器关掉），而是**解锁界面功能**的钥匙。
     */
    private val NEED_UPGRADE_LORE: List<Component> = listOf(
        Component.text("需要升级模块才能更改", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false),
        Component.text("红石 → 基础；分配 → 强化；过滤 → 高级", NamedTextColor.DARK_GRAY)
    )

    private val STRUCTURE: Array<String> = buildStructure()

    private fun buildStructure(): Array<String> {
        val grid = Array(5) { CharArray(9) { BG } }
        grid[0][0] = MODE
        grid[0][1] = REDSTONE
        grid[0][2] = DISTRIBUTION
        grid[0][3] = FILTER_MODE
        var i = 0
        for (col in 0 until 9) grid[1][col] = FILTER_CHARS[i++]
        for (col in 0 until 9) grid[2][col] = FILTER_CHARS[i++]
        grid[3][0] = UPGRADE
        grid[3][1] = ADD
        grid[3][2] = REMOVE
        grid[3][3] = CLEAR
        grid[4][0] = HELP
        return grid.map { String(it) }.toTypedArray()
    }

    // ---------------------------------------------------------------- 打开

    /** 给 [player] 打开 [conduit] 上 [face] 这一面的配置界面。 */
    fun open(player: Player, conduit: ConduitBlock, face: BlockFace) {
        val gui = Gui.builder()
            .setStructure(*STRUCTURE)
            .setBackground(ItemWrapper(filler()))
            .build()

        // 过滤器格子的 Item 引用 —— 增删过滤器后要一起刷新
        val filterItems = ArrayList<Item>()
        val refreshFilters: () -> Unit = { filterItems.forEach(Item::notifyWindows) }

        /**
         * 让**整个界面重绘一遍**。
         *
         * ⚠️ 只在"某个操作会改变**别的格子**的内容"时才需要。已知的几处：
         * - 改升级模块 → 帮助条目的「本面升级 / 本面上限」、以及三个模式按钮的锁定状态；
         * - 改本面模式（普通/抽取/断路）→ 帮助条目里"此面限不限流"那段；
         * - 改过滤模式 → 过滤器格子能不能点。
         * 之前这几处都只重绘了自己那一个格子，于是"状态变了但字还是旧的"（用户实测）。
         */
        val refreshAll: () -> Unit = { gui.notifyWindows() }

        // 这一面的连接模式（普通 / 抽取 / 断路）—— 和扳手是同一个开关，
        // 放在界面上是为了给玩家一个**可靠、看得见**的断开途径。
        gui.setItem(MODE, modeButton(conduit, face, refreshAll))

        // 红石 / 分配 / 过滤模式：三个循环按钮
        gui.setItem(REDSTONE, redstoneButton(conduit, face))
        gui.setItem(DISTRIBUTION, distributionButton(conduit, face))
        gui.setItem(FILTER_MODE, filterModeButton(conduit, face, refreshAll))

        // 过滤器列表
        FILTER_CHARS.forEachIndexed { index, ch ->
            val item = filterSlot(conduit, face, index, refreshFilters)
            filterItems += item
            gui.setItem(ch, item)
        }

        // 升级槽（只有能量管有 —— 对齐 Pipez 的 hasUpgrade()）
        if (conduit.acceptsUpgrade()) {
            val upgradeSlot = VirtualInventory(1)
            // 用副本填进视图：真正的存储在管子上（`setSideUpgrade` 会写 PDC）
            conduit.sideUpgrade(face)?.clone()?.let { upgradeSlot.setItem(UpdateReason.SUPPRESSED, 0, it) }
            upgradeSlot.addPreUpdateHandler { event ->
                val incoming = event.newItem ?: return@addPreUpdateHandler
                // 只收升级模块，别的物品一律挡回去（不然会凭空消失）
                if (RebarItem.fromStack(incoming, UpgradeItem::class.java) == null) {
                    event.isCancelled = true
                }
            }
            upgradeSlot.addPostUpdateHandler { event ->
                val item = event.newItem
                // 兜底：**升级槽只允许 1 个**。升级物品本身已经是最大堆叠 1，
                // 但老存档 / 别的途径塞进来的整摞也要钳掉，多余的原样还给玩家。
                if (item != null && item.amount > 1) {
                    val one = item.clone().apply { amount = 1 }
                    val rest = item.clone().apply { amount = item.amount - 1 }
                    upgradeSlot.setItem(UpdateReason.SUPPRESSED, 0, one)
                    val overflow = player.inventory.addItem(rest)
                    overflow.values.forEach { player.world.dropItem(player.location, it) }
                }
                // 虚拟库存只是"视图"，真正的存储在管子上（会写 PDC）
                conduit.setSideUpgrade(face, upgradeSlot.getItem(0))
                // ⚠️ 档位变了 → **必须让界面重绘**。
                // 帮助条目（"本面升级 / 本面上限"）和三个模式按钮的**锁定状态**都跟着档位走；
                // 不重绘的话，玩家把模块拖走之后那行字还写着"究极"、按钮也还是解锁状态
                // （用户实测："把升级拿出来还是那个挡位"）。
                gui.notifyWindows()
            }
            gui.setInventory(UPGRADE, upgradeSlot)
        }

        gui.setItem(ADD, addButton(conduit, face, refreshFilters))
        gui.setItem(REMOVE, removeButton(conduit, face, refreshFilters))
        gui.setItem(CLEAR, clearButton(conduit, face, refreshFilters))
        // ⚠️ 用 **Supplier 重载**，让这条每次重绘都重新生成 —— 它要反映"当前档位"，
        // 而档位会被升级槽改掉（`Item.simple(...)` 只渲染一次，会一直是旧内容）。
        gui.setItem(HELP) { helpItem(conduit, face) }

        Window.builder()
            .setViewer(player)
            .setTitle(Component.text("${pipeName(conduit.pipeType)} · ${faceName(face)}"))
            .setUpperGui(gui)
            .build()
            .open()
    }

    // ---------------------------------------------------------------- 循环按钮

    /**
     * 这一面的**连接模式**按钮：普通 / 抽取 / 断路。
     *
     * 和扳手（潜行 + 右键）改的是**同一个值**，只是这里看得见、点得到。
     * 循环顺序 = [FaceMode.TERMINAL_CYCLE]（普通 → 抽取 → 断路）。
     */
    private fun modeButton(conduit: ConduitBlock, face: BlockFace, refreshAll: () -> Unit): Item =
        Item.builder()
            .setItemProvider { _ ->
                val mode = conduit.faceMode(face)
                val stack = ItemStack(materialForMode(mode))
                val meta = stack.itemMeta
                meta.displayName(Component.text("本面模式：${modeName(mode)}", colorForMode(mode)))
                val lore = ArrayList<Component>()
                lore += Component.text("左键：下一个模式", NamedTextColor.GRAY)
                lore += Component.text("右键：上一个模式", NamedTextColor.GRAY)
                lore += Component.text("顺序：普通 → 抽取 → 断路", NamedTextColor.DARK_GRAY)
                lore += when (mode) {
                    FaceMode.NORMAL -> Component.text("普通：正常连（双向）", NamedTextColor.GREEN)
                    FaceMode.EXTRACT -> Component.text("抽取：只从这个邻居往外取", NamedTextColor.GOLD)
                    FaceMode.NONE -> Component.text("断路：这一面不连", NamedTextColor.RED)
                }
                meta.lore(lore)
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .updateOnClick()
            .addClickHandler { click ->
                val cycle = FaceMode.TERMINAL_CYCLE
                val index = cycle.indexOf(conduit.faceMode(face)).coerceAtLeast(0)
                val step = if (click.clickType.isRightClick) cycle.size - 1 else 1
                conduit.setFaceMode(face, cycle[(index + step) % cycle.size])
                // 本面模式变了 → 帮助条目里"此面限不限流"那段要跟着变
                refreshAll()
            }
            .build()

    private fun redstoneButton(conduit: ConduitBlock, face: BlockFace): Item =
        Item.builder()
            .setItemProvider { _ ->
                // 没插升级模块 → 锁（对标 Pipez 的 canChangeRedstoneMode(null) == false）
                if (!conduit.canChangeRedstone(face)) return@setItemProvider lockedIcon("红石模式", NEED_UPGRADE_LORE)
                val mode = conduit.redstoneMode(face)
                val stack = ItemStack(materialForRedstone(mode))
                val meta = stack.itemMeta
                meta.displayName(Component.text("红石模式：${redstoneName(mode)}", NamedTextColor.RED))
                meta.lore(
                    listOf(
                        Component.text("左键 / 右键：循环切换", NamedTextColor.GRAY),
                        Component.text("当前：${redstoneHint(mode)}", NamedTextColor.DARK_GRAY)
                    )
                )
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .updateOnClick()
            .addClickHandler { click ->
                if (!conduit.canChangeRedstone(face)) return@addClickHandler
                val mode = conduit.redstoneMode(face)
                conduit.setRedstoneMode(face, if (click.clickType.isRightClick) mode.previous else mode.next)
            }
            .build()

    private fun distributionButton(conduit: ConduitBlock, face: BlockFace): Item =
        Item.builder()
            .setItemProvider { _ ->
                // 第一关：管道类型支不支持（能量 / 流体 = 路由在 Rebar 手里，改不了）
                if (!conduit.pipeSupportsDistribution()) {
                    return@setItemProvider lockedIcon("分配模式：Rebar 默认", LOCKED_LORE)
                }
                // 第二关：档位够不够（要"强化"及以上）
                if (!conduit.canChangeDistribution(face)) {
                    return@setItemProvider lockedIcon("分配模式", NEED_UPGRADE_LORE)
                }
                val mode = conduit.distribution(face)
                val stack = ItemStack(Material.HOPPER)
                val meta = stack.itemMeta
                meta.displayName(Component.text("分配模式：${distributionName(mode)}", NamedTextColor.GOLD))
                meta.lore(
                    listOf(
                        Component.text("左键 / 右键：循环切换", NamedTextColor.GRAY),
                        Component.text("顺序：最近 → 最远 → 轮流 → 随机", NamedTextColor.DARK_GRAY)
                    )
                )
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .updateOnClick()
            .addClickHandler { click ->
                if (!conduit.canChangeDistribution(face)) return@addClickHandler
                val mode = conduit.distribution(face)
                conduit.setDistribution(face, if (click.clickType.isRightClick) mode.previous else mode.next)
            }
            .build()

    private fun filterModeButton(conduit: ConduitBlock, face: BlockFace, refreshAll: () -> Unit): Item =
        Item.builder()
            .setItemProvider { _ ->
                // 第一关：能量管没有过滤器
                if (!conduit.pipeSupportsFilter()) {
                    return@setItemProvider lockedIcon("过滤模式：不适用", LOCKED_LORE)
                }
                // 第二关：档位够不够（要"高级"及以上）
                if (!conduit.canChangeFilter(face)) {
                    return@setItemProvider lockedIcon("过滤模式", NEED_UPGRADE_LORE)
                }
                val mode = conduit.filterMode(face)
                val stack = ItemStack(materialForFilter(mode))
                val meta = stack.itemMeta
                meta.displayName(Component.text("过滤模式：${filterName(mode)}", NamedTextColor.LIGHT_PURPLE))
                meta.lore(
                    listOf(
                        Component.text("左键 / 右键：循环切换", NamedTextColor.GRAY),
                        Component.text("白名单 = 只让列表里的过", NamedTextColor.DARK_GRAY),
                        Component.text("黑名单 = 列表里的都挡住", NamedTextColor.DARK_GRAY),
                        // 诚实标注：能配置，但过滤效果还没接上（传输在 Rebar 手里）
                        Component.text("⚠ 过滤效果待接入", NamedTextColor.DARK_GRAY)
                    )
                )
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .updateOnClick()
            .addClickHandler { click ->
                if (!conduit.canChangeFilter(face)) return@addClickHandler
                val mode = conduit.filterMode(face)
                conduit.setFilterMode(face, if (click.clickType.isRightClick) mode.previous else mode.next)
                // 过滤模式变了 → 过滤器格子的"能不能点"跟着变
                refreshAll()
            }
            .build()

    // ---------------------------------------------------------------- 过滤器

    private fun filterSlot(
        conduit: ConduitBlock,
        face: BlockFace,
        index: Int,
        refresh: () -> Unit
    ): Item =
        Item.builder()
            .setItemProvider { _ ->
                val existing = conduit.filters(face).getOrNull(index)
                if (existing != null) {
                    val stack = existing.clone().apply { amount = 1 }
                    val meta = stack.itemMeta
                    val lore = ArrayList<Component>()
                    meta.lore()?.let(lore::addAll)
                    lore += Component.text("左键：移除这条过滤", NamedTextColor.RED)
                    meta.lore(lore)
                    stack.itemMeta = meta
                    ItemWrapper(stack)
                } else if (!conduit.canChangeFilter(face)) {
                    // 能量管没有过滤器 → 整片屏障
                    val stack = ItemStack(Material.BARRIER)
                    val meta = stack.itemMeta
                    meta.displayName(
                        Component.text("此管道没有过滤器", NamedTextColor.LIGHT_PURPLE)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(filterLockLore(conduit))
                    stack.itemMeta = meta
                    ItemWrapper(stack)
                } else {
                    val stack = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    val meta = stack.itemMeta
                    meta.displayName(
                        Component.text("空过滤位 ${index + 1}", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(Component.text("手上拿着物品时左键 = 加入过滤", NamedTextColor.GRAY)))
                    stack.itemMeta = meta
                    ItemWrapper(stack)
                }
            }
            .updateOnClick()
            .addClickHandler { click ->
                if (!conduit.canChangeFilter(face)) return@addClickHandler
                val player = click.player
                val existing = conduit.filters(face).getOrNull(index)
                if (existing != null) {
                    // 点已有过滤 → 移除，物品还给玩家
                    conduit.removeFilter(face, index)
                    refresh()
                } else {
                    // 点空格子且手上有物品 → 加进去
                    val cursor = player.itemOnCursor
                    if (cursor.type != Material.AIR) {
                        if (conduit.addFilter(face, cursor)) refresh()
                    }
                }
            }
            .build()

    private fun addButton(conduit: ConduitBlock, face: BlockFace, refresh: () -> Unit): Item =
        Item.builder()
            .setItemProvider { _ ->
                if (!conduit.canChangeFilter(face)) return@setItemProvider lockedIcon("添加过滤", filterLockLore(conduit))
                val stack = ItemStack(Material.LIME_DYE)
                val meta = stack.itemMeta
                meta.displayName(Component.text("添加过滤", NamedTextColor.GREEN))
                meta.lore(listOf(Component.text("把手上拿着的物品加进过滤", NamedTextColor.GRAY)))
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .addClickHandler { click ->
                if (!conduit.canChangeFilter(face)) return@addClickHandler
                val cursor = click.player.itemOnCursor
                if (cursor.type != Material.AIR && conduit.addFilter(face, cursor)) refresh()
            }
            .build()

    private fun removeButton(conduit: ConduitBlock, face: BlockFace, refresh: () -> Unit): Item =
        Item.builder()
            .setItemProvider { _ ->
                if (!conduit.canChangeFilter(face)) return@setItemProvider lockedIcon("移除最后一条", filterLockLore(conduit))
                val stack = ItemStack(Material.RED_DYE)
                val meta = stack.itemMeta
                meta.displayName(Component.text("移除最后一条", NamedTextColor.RED))
                meta.lore(listOf(Component.text("删掉过滤列表里的最后一条", NamedTextColor.GRAY)))
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .addClickHandler {
                if (!conduit.canChangeFilter(face)) return@addClickHandler
                val list = conduit.filters(face)
                if (list.isNotEmpty()) {
                    conduit.removeFilter(face, list.size - 1)
                    refresh()
                }
            }
            .build()

    private fun clearButton(conduit: ConduitBlock, face: BlockFace, refresh: () -> Unit): Item =
        Item.builder()
            .setItemProvider { _ ->
                if (!conduit.canChangeFilter(face)) return@setItemProvider lockedIcon("清空过滤", filterLockLore(conduit))
                val stack = ItemStack(Material.BARRIER)
                val meta = stack.itemMeta
                meta.displayName(Component.text("清空过滤", NamedTextColor.DARK_RED))
                meta.lore(listOf(Component.text("删掉这个面的所有过滤", NamedTextColor.GRAY)))
                stack.itemMeta = meta
                ItemWrapper(stack)
            }
            .addClickHandler {
                if (!conduit.canChangeFilter(face)) return@addClickHandler
                conduit.clearFilters(face)
                refresh()
            }
            .build()

    /** 过滤器被锁时该显示哪种原因：管道类型不支持 → 底层限制；否则就是缺升级模块。 */
    private fun filterLockLore(conduit: ConduitBlock): List<Component> =
        if (!conduit.pipeSupportsFilter()) LOCKED_LORE else NEED_UPGRADE_LORE

    /** "改不了"的按钮统一长这样：屏障 + 红字说明（[LOCKED_LORE] 或 [NEED_UPGRADE_LORE]）。 */
    private fun lockedIcon(label: String, lore: List<Component>): ItemWrapper {
        val stack = ItemStack(Material.BARRIER)
        val meta = stack.itemMeta
        meta.displayName(
            Component.text(label, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(lore)
        stack.itemMeta = meta
        return ItemWrapper(stack)
    }

    // ---------------------------------------------------------------- 其它

    private fun helpItem(conduit: ConduitBlock, face: BlockFace): Item {
        val stack = ItemStack(Material.BOOK)
        val meta = stack.itemMeta
        meta.displayName(Component.text("怎么用", NamedTextColor.YELLOW))
        val lore = ArrayList<Component>()
        lore += Component.text("这一页配置的是【${faceName(face)}】", NamedTextColor.AQUA)
        lore += Component.text("· 本面模式：普通 / 抽取 / 断路（左键循环）", NamedTextColor.GRAY)
        lore += Component.text("· 红石模式：真的生效", NamedTextColor.GRAY)
        if (conduit.canChangeDistribution(face)) {
            lore += Component.text("· 分配模式：左键循环", NamedTextColor.GRAY)
        } else {
            lore += Component.text("· 分配模式：改不了（屏障）", NamedTextColor.DARK_GRAY)
        }
        if (conduit.canChangeFilter(face)) {
            lore += Component.text("· 过滤器：手上拿物品点空格加入，点已有移除", NamedTextColor.GRAY)
        } else {
            lore += Component.text("· 过滤器：改不了（屏障）", NamedTextColor.DARK_GRAY)
        }
        if (conduit.acceptsUpgrade()) {
            lore += Component.text("· 升级槽：拖升级模块进去", NamedTextColor.GRAY)
            // 升级模块的**真正用处** —— 它是解锁上面那几个按钮的钥匙（对标 Pipez Upgrade）
            lore += Component.text("  红石=基础 / 分配=强化 / 过滤=高级", NamedTextColor.DARK_GRAY)
        }
        lore += Component.text("· 潜行 + 扳手右键：快速切换这一面的模式", NamedTextColor.GRAY)
        if (conduit.acceptsUpgrade()) {
            val own = conduit.upgradeTierOrNull(face)
            lore += Component.text(
                "本面升级：${own?.let(::tierName) ?: "未安装"}",
                if (own == null) NamedTextColor.RED else NamedTextColor.GRAY
            )
            // ⚠️ 档位**只在抽取面上有电学意义** —— 对标 Pipez 的 `pullEnergy`：
            // 速率是"正在抽取的那一面"的升级，接收面不参与那次搬运。
            if (!conduit.faceMode(face).directional) {
                lore += Component.text("  此面不限流；速率由【抽取面】决定", NamedTextColor.DARK_GRAY)
            } else if (!PipezConfig.tierPowerLimit) {
                lore += Component.text("  限流已在 config 里关闭（不限流）", NamedTextColor.DARK_GRAY)
            } else {
                // ⚠️ `UnitFormat.WATTS.format(...)` 返回的是 `UnitFormat.Formatted`（ComponentLike），
                // **不能**塞进字符串模板 —— 那样会调它的 `toString()`，打出 `...Formatted@52be6103`。
                // 必须 `.build()` 成 Component 再拼。
                lore += Component.text("本面上限：", NamedTextColor.GRAY)
                    .append(UnitFormat.WATTS.format(PipezConfig.capacity(conduit.tierOf(face))).build())
            }
        }
        meta.lore(lore)
        stack.itemMeta = meta
        return Item.simple(ItemWrapper(stack))
    }

    private fun filler(): ItemStack {
        val stack = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = stack.itemMeta
        meta.displayName(Component.text(" "))
        stack.itemMeta = meta
        return stack
    }

    // ---------------------------------------------------------------- 文案

    private fun pipeName(type: PipeType): String = when (type) {
        PipeType.ENERGY -> "能量管道"
        PipeType.FLUID -> "流体管道"
    }

    private fun faceName(face: BlockFace): String = when (face) {
        BlockFace.UP -> "上面"
        BlockFace.DOWN -> "下面"
        BlockFace.NORTH -> "北面"
        BlockFace.SOUTH -> "南面"
        BlockFace.EAST -> "东面"
        BlockFace.WEST -> "西面"
        else -> face.name
    }

    private fun redstoneName(mode: RedstoneMode): String = when (mode) {
        RedstoneMode.IGNORED -> "忽略"
        RedstoneMode.OFF_WHEN_POWERED -> "有信号时停"
        RedstoneMode.ON_WHEN_POWERED -> "有信号才动"
        RedstoneMode.ALWAYS_OFF -> "永久停止"
    }

    private fun redstoneHint(mode: RedstoneMode): String = when (mode) {
        RedstoneMode.IGNORED -> "不看红石"
        RedstoneMode.OFF_WHEN_POWERED -> "通电就断连"
        RedstoneMode.ON_WHEN_POWERED -> "通电才连"
        RedstoneMode.ALWAYS_OFF -> "永远不连"
    }

    private fun distributionName(mode: Distribution): String = when (mode) {
        Distribution.NEAREST -> "最近优先"
        Distribution.FURTHEST -> "最远优先"
        Distribution.ROUND_ROBIN -> "轮流"
        Distribution.RANDOM -> "随机"
    }

    private fun filterName(mode: FilterMode): String = when (mode) {
        FilterMode.WHITELIST -> "白名单"
        FilterMode.BLACKLIST -> "黑名单"
    }

    private fun modeName(mode: FaceMode): String = when (mode) {
        FaceMode.NORMAL -> "普通"
        FaceMode.EXTRACT -> "抽取"
        FaceMode.NONE -> "断路"
    }

    private fun colorForMode(mode: FaceMode): NamedTextColor = when (mode) {
        FaceMode.NORMAL -> NamedTextColor.GREEN
        FaceMode.EXTRACT -> NamedTextColor.GOLD
        FaceMode.NONE -> NamedTextColor.RED
    }

    private fun materialForMode(mode: FaceMode): Material = when (mode) {
        FaceMode.NORMAL -> Material.LIME_DYE
        FaceMode.EXTRACT -> Material.BLACK_DYE
        FaceMode.NONE -> Material.GRAY_DYE
    }

    private fun materialForRedstone(mode: RedstoneMode): Material = when (mode) {
        RedstoneMode.IGNORED -> Material.GUNPOWDER
        RedstoneMode.OFF_WHEN_POWERED -> Material.REDSTONE_TORCH
        RedstoneMode.ON_WHEN_POWERED -> Material.REDSTONE_BLOCK
        RedstoneMode.ALWAYS_OFF -> Material.LEVER
    }

    private fun materialForFilter(mode: FilterMode): Material = when (mode) {
        FilterMode.WHITELIST -> Material.WHITE_DYE
        FilterMode.BLACKLIST -> Material.BLACK_DYE
    }

    private fun tierName(tier: ConduitTier): String = when (tier) {
        ConduitTier.BASIC -> "基础"
        ConduitTier.IMPROVED -> "强化"
        ConduitTier.ADVANCED -> "高级"
        ConduitTier.ULTIMATE -> "究极"
    }
}

package top.mc506lw.rebar.pipez.conduit

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.ElectricRebarBlock
import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.block.interfaces.FluidRebarBlock
import io.github.pylonmc.rebar.block.interfaces.InteractRebarBlockHandler
import io.github.pylonmc.rebar.event.api.annotation.MultiHandler
import io.github.pylonmc.rebar.datatypes.RebarSerializers
import io.github.pylonmc.rebar.electricity.ElectricNetwork
import io.github.pylonmc.rebar.electricity.ElectricityManager
import io.github.pylonmc.rebar.electricity.nodes.ElectricConnectorNode
import io.github.pylonmc.rebar.electricity.nodes.ElectricNode
import io.github.pylonmc.rebar.entity.display.ItemDisplayBuilder
import io.github.pylonmc.rebar.entity.display.transform.TransformBuilder
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.github.pylonmc.rebar.util.position.BlockPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import kotlin.math.abs
import top.mc506lw.rebar.pipez.PipezConfig
import top.mc506lw.rebar.pipez.PipezKeys
import top.mc506lw.rebar.pipez.fluid.ConduitFluid
import top.mc506lw.rebar.pipez.items.ConduitItem
import top.mc506lw.rebar.pipez.items.UpgradeItem

/**
 * 管段方块 —— 整套设计的核心。
 *
 * ## 一根管 = 一种介质
 *
 * 这个类同时服务**能量管**和**流体管**；具体是哪种由方块的内容键决定
 * （见 [pipeType]）。两种类型的区别只有三处：
 * - 渲染颜色（[pipeType] 决定彩条材质）
 * - 接哪套网络：能量 → 电力节点；流体 → [ConduitFluid]
 * - 互不连通：[connectsTo] 只在同类之间成立
 *
 * ## 四条设计约束
 *
 * 1. **一个管段 = 一个连接节点，不是六个端口。**
 *    每面一个端口的话，一万根管子就是六万个节点 + 六万个端口展示实体，直接崩。
 *    这里只持有**一个** `ElectricConnectorNode`（仅能量管），方向由"和哪个邻居连"表达。
 *
 * 2. **形状不持久化。**
 *    [shapeMask] 完全由六个邻居推导。PDC 里只存两样东西，而且都只在非默认值时才写：
 *    "被扳手禁用的面"（1 byte）和"档位"（1 byte）。
 *
 * 3. **不 tick。**
 *    不实现 `TickingRebarBlock` —— tick 在 Rebar 里是 opt-in 的，被动管段一 tick 都不花。
 *
 * 4. **形状走展示实体。**
 *    [getBlockTextureItem] 把形状掩码写进自定义模型数据，配资源包就能出弯头/三通/十字。
 *    这个展示实体 Rebar 本来就给每个方块建了，所以不额外增加开销。
 *
 * ## 幂等性
 *
 * Rebar 会把节点写进方块 PDC，而且**没有 API 能删除节点**，
 * 所以 [ensureNode] 必须幂等：已有就复用，没有才建。
 * 动态变化的只有"边"（[ElectricNode.connect] / [disconnectFrom]，边可以随便增删）。
 */
class ConduitBlock :
    RebarBlock,
    ElectricRebarBlock,
    BlockBreakRebarBlockHandler,
    InteractRebarBlockHandler {

    /**
     * 每个面的连接模式（无 / 抽取 / 接收）。
     *
     * 这是 [FaceMode] 取代旧 `disabledFaces` 6 bit 掩码后的**唯一真相来源**；
     * 掩码只是它的一个退化投影（见 [write]）。
     *
     * 默认表：六个面都是 [FaceMode.DEFAULT]（普通）—— 等价于旧版的"没被扳手禁用"。
     * 这样老存档的管子升级后行为不变（该连的还是连）。
     *
     * ⚠️ 只在**非默认值**时才写 PDC：一张全默认的表等于没配过，
     * 不写就不占存档空间（万级管道的存档体积是设计约束之一）。
     */
    private val faceModes = HashMap<BlockFace, FaceMode>()

    /**
     * **每个面**插着的升级模块（物品本体）。
     *
     * ## 为什么是"插着"而不是"吃掉"
     *
     * Pipez 的 `UpgradeTileEntity.upgradeInventory` 是
     * `NonNullList.withSize(Direction.values().length, ItemStack.EMPTY)` ——
     * **每个面一格，存的是那个升级模块物品本身**，玩家随时能拿回去。
     * 档位不是独立字段，而是**从槽里的物品推导**出来的
     * （`getUpgrade(side)` = `stack.getItem() as? UpgradeItem`）。
     *
     * 所以这里也照做：`tierOf(face)` 直接看这一格的物品。
     * 拔掉模块 = 降回基础档 —— 这是 Pipez 的行为，玩家能预期。
     *
     * ## 什么时候会被清掉
     *
     * Pipez 的 `UpgradeTileEntity.setExtracting(side, false)`：**一旦某个面不再抽取，
     * 那个面的升级模块会被丢出来**（`Containers.dropContents`）。
     * 这里同样处理，见 [setFaceMode]。
     */
    private val sideUpgrades = HashMap<BlockFace, ItemStack>()

    /** 每个面的红石模式。默认 [RedstoneMode.DEFAULT]。 */
    private val sideRedstone = HashMap<BlockFace, RedstoneMode>()

    /** 每个面的分配模式。默认 [Distribution.DEFAULT]。 */
    private val sideDistribution = HashMap<BlockFace, Distribution>()

    /** 每个面的过滤模式。默认 [FilterMode.DEFAULT]。 */
    private val sideFilterMode = HashMap<BlockFace, FilterMode>()

    /**
     * 每个面的**过滤器列表** —— 对标 Pipez 的 `getFilters(side, pipeType)`。
     *
     * Pipez 的过滤器是一个复杂的对象（tag + metadata + 精确匹配…），
     * 我们这里先落成**一个物品列表**（ItemStack 本身带 NBT），
     * 够表达"只让这些物品过 / 除了这些都能过"这两种最常见的用法。
     *
     * ⚠️ 现在**只存不生效**：能量管在 Pipez 里压根没有过滤器
     * （`EnergyPipeType.hasFilter() == false`），流体侧走 Rebar 的 `FluidManager`，
     * 我们插不进去。等自己实现传输时再接。
     */
    private val sideFilters = HashMap<BlockFace, MutableList<ItemStack>>()

    /**
     * **旧版**的整管档位，只用于向后兼容读取。
     *
     * 0.1.37 之前档位是**整根管一个**（`PipezKeys.TIER`）。现在改成每面一个模块，
     * 所以老存档读出来的值只当作"六个面都装了对应档位的模块"的等价物 ——
     * 见 [loadSideSettings]。
     */
    private var legacyTier: ConduitTier = ConduitTier.BASIC

    /**
     * 本管段的类型（能量 / 流体），由方块的内容键推导。
     *
     * 旧版只有一个 `pipez:conduit` 键，那时它"电+流体都传"；现在把它归为能量管
     * （能量是主用途），老存档升级后就是一根能量管。
     */
    val pipeType: PipeType = run {
        val fromKey = PipeType.fromItemKey(key.key)
        // 旧键 `conduit` 没有 `pipe_` 前缀 → fromItemKey 返回 null → 回退成能量管
        fromKey ?: PipeType.ENERGY
    }

    /** 上次重建显示实体时用的形状掩码。运行期状态，不需要持久化。 */
    private var lastDisplayMask: Int = -1

    /**
     * 已经登记过"端口加载回调"的 (机器位置, 面)。
     *
     * [registerPortLoadWatch] 会问机器要一个"端口实体加载后"的回调；
     * 这个表保证同一格只登记一次（`refresh()` 会被反复调用）。
     * 纯运行期状态，区块卸载后随本对象一起消失。
     */
    private val portWatchKeys = HashSet<PortWatchKey>()

    private data class PortWatchKey(val pos: BlockPosition, val face: BlockFace)

    constructor(block: Block, context: BlockCreateContext) : super(block, context)

    constructor(block: Block, pdc: PersistentDataContainer) : super(block, pdc) {
        loadFaceModes(pdc)
        loadSideSettings(pdc)
    }

    /**
     * 从 PDC 还原"每面的升级模块 + 红石/分配/过滤模式"。
     *
     * 编码：
     * - [PipezKeys.SIDE_UPGRADES]：`"FACE=BASE64;FACE=BASE64"`（物品用
     *   `ItemStack.serializeAsBytes` + Base64 —— 不依赖任何非公开 API）
     * - [PipezKeys.SIDE_REDSTONE] / `SIDE_DISTRIBUTION` / `SIDE_FILTER_MODE`：
     *   6 字符字符串，顺序 = [CARDINAL_FACES]，字符 = 枚举序号
     *
     * ⚠️ **向后兼容**：老存档只有整管的 [PipezKeys.TIER]。读出来就当作
     * "六个面都插了对应档位的模块"（[legacyTier]），行为保持不变。
     */
    private fun loadSideSettings(pdc: PersistentDataContainer) {
        legacyTier = ConduitTier.fromOrdinal(
            pdc.get(PipezKeys.TIER, PersistentDataType.BYTE)?.toInt() ?: 0
        )

        pdc.get(PipezKeys.SIDE_UPGRADES, PersistentDataType.STRING)?.let { encoded ->
            for (entry in encoded.split(';')) {
                if (entry.isEmpty()) continue
                val split = entry.split('=', limit = 2)
                if (split.size != 2) continue
                val face = faceByName(split[0]) ?: continue
                runCatching {
                    ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(split[1]))
                }.getOrNull()?.let { sideUpgrades[face] = it }
            }
        }

        readOrdinalRow(pdc, PipezKeys.SIDE_REDSTONE) { face, ordinal ->
            sideRedstone[face] = RedstoneMode.fromOrdinal(ordinal)
        }
        readOrdinalRow(pdc, PipezKeys.SIDE_DISTRIBUTION) { face, ordinal ->
            sideDistribution[face] = Distribution.fromOrdinal(ordinal)
        }
        readOrdinalRow(pdc, PipezKeys.SIDE_FILTER_MODE) { face, ordinal ->
            sideFilterMode[face] = FilterMode.fromOrdinal(ordinal)
        }

        // 每面过滤器列表："FACE=b64,b64;FACE=b64"
        pdc.get(PipezKeys.SIDE_FILTERS, PersistentDataType.STRING)?.let { encoded ->
            for (entry in encoded.split(';')) {
                if (entry.isEmpty()) continue
                val split = entry.split('=', limit = 2)
                if (split.size != 2) continue
                val face = faceByName(split[0]) ?: continue
                val list = ArrayList<ItemStack>()
                for (itemB64 in split[1].split(',')) {
                    if (itemB64.isEmpty()) continue
                    runCatching {
                        ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(itemB64))
                    }.getOrNull()?.let(list::add)
                }
                if (list.isNotEmpty()) sideFilters[face] = list
            }
        }
    }

    /** 读一行"6 字符序号表"。缺字符就跳过（缺的用默认值）。 */
    private inline fun readOrdinalRow(
        pdc: PersistentDataContainer,
        key: org.bukkit.NamespacedKey,
        apply: (BlockFace, Int) -> Unit
    ) {
        val encoded = pdc.get(key, PersistentDataType.STRING) ?: return
        for ((index, face) in CARDINAL_FACES.withIndex()) {
            val char = encoded.getOrNull(index) ?: continue
            apply(face, char - '0')
        }
    }

    private fun faceByName(name: String): BlockFace? =
        CARDINAL_FACES.firstOrNull { it.name == name }

    /** 把"6 字符序号表"编码出来；全默认返回 null（不写 PDC）。 */
    private inline fun encodeOrdinalRow(default: Int, valueOf: (BlockFace) -> Int): String? {
        val values = CARDINAL_FACES.map(valueOf)
        if (values.all { it == default }) return null
        return values.joinToString("") { it.toString() }
    }

    /**
     * 从 PDC 还原每面模式，并**优先读新模式表、读不到再退回旧禁用掩码**。
     *
     * 编码：新键 `PipezKeys.FACE_MODES` 是一个 6 字符的字符串，
     * 字符 `i` 就是 `ConduitFace` 的下标，值是该面的 [FaceMode] 序号（0/1/2）。
     * 用字符串而不是 6 个 byte，是因为一个键读一次就够，
     * 而且调试时 `/data get` 一眼能看懂（`"222222"` = 全 INSERT）。
     *
     * ⚠️ **向后兼容**：老存档只有 [PipezKeys.DISABLED_FACES]。
     * 那种情况下按 [FaceMode.tableFromDisabledMask] 还原（禁用的面 → NONE，
     * 其余 → INSERT），保证老管子升级后"该连的还连着"。
     */
    private fun loadFaceModes(pdc: PersistentDataContainer) {
        val encoded = pdc.get(PipezKeys.FACE_MODES, PersistentDataType.STRING)
        if (encoded != null) {
            for ((index, face) in CARDINAL_FACES.withIndex()) {
                val char = encoded.getOrNull(index) ?: continue
                val ordinal = char - '0'
                faceModes[face] = FaceMode.fromOrdinal(ordinal)
            }
            return
        }
        // 旧存档：只有禁用掩码
        val disabled = pdc.get(PipezKeys.DISABLED_FACES, PersistentDataType.BYTE)?.toInt() ?: 0
        if (disabled == 0) return
        faceModes.putAll(FaceMode.tableFromDisabledMask(disabled))
    }

    /** 把模式表编码成 6 字符字符串（顺序 = [CARDINAL_FACES]）。 */
    private fun encodeFaceModes(): String =
        CARDINAL_FACES.joinToString("") { (faceModes[it] ?: FaceMode.DEFAULT).ordinal.toString() }

    /** 模式表是不是"全默认"（六个面都是 [FaceMode.DEFAULT]）—— 是的话就不写 PDC。 */
    private fun faceModesAreDefault(): Boolean =
        CARDINAL_FACES.all { (faceModes[it] ?: FaceMode.DEFAULT) == FaceMode.DEFAULT }

    override fun postInitialise() {
        healLegacyBlockType()
        ensureHolderRegistered()
        ensureNodeIfEnergy()
        ConduitNetwork.markAround(block.position)
    }

    override fun postLoad() {
        super.postLoad()
        healLegacyBlockType()
        ensureHolderRegistered()
        ensureNodeIfEnergy()
        // ⚠️ 孤儿实体清扫**只在这里做**（加载时），不要放进 [clearDisplays]。
        // 它要 `world.getNearbyEntities(1.5³)` 扫一遍附近实体；放进每次重建里的话，
        // 一个几千格的管网就是"每格每次重建一次实体查询"，直接拖垮服务器。
        // 它本来也只是给"插件热更后旧实体没人认领"兜底用的，加载时扫一次足够。
        purgeOrphanedDisplays()
        ConduitNetwork.markAround(block.position)
    }

    /**
     * 右键管道 → 打开配置界面（复刻 Pipez 的 `onPipeSideActivated`）。
     *
     * ## 触发条件
     *
     * Pipez 只在**"抽取"那一面**能开；这里放宽成**任意面** ——
     * 因为点到普通面时毫无反馈，玩家很容易以为界面坏了。
     * 开出来的界面里六个面都能配，所以放宽不丢功能。
     *
     * ## ⚠️ 潜行右键必须放行
     *
     * 潜行 + 右键是**扳手**的手势（见 [WrenchItem]），这里必须让开，
     * 否则两个功能会抢同一个事件。
     */
    @MultiHandler(priorities = [EventPriority.HIGH])
    override fun onInteractedWith(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.player.isSneaking) return

        // 手上拿着这些物品时**不弹界面** —— 那个手势属于它们自己：
        // - 升级模块：插进这一面的槽（见 UpgradeItem）
        // - 管道方块：正常放置，别抢
        //
        // ⚠️ 扳手**不在此列** —— 扳手是"潜行才生效"的（见 WrenchItem），
        // 不潜行时应该弹界面，而不是什么都不发生。之前把扳手也挡掉了，
        // 结果玩家"忘了潜行 → 点了没反应 → 以为扳手坏了"。见 [isPlacementItem]。
        val held = event.player.inventory.itemInMainHand
        if (held.type != Material.AIR && isPlacementItem(held)) return

        val face = event.blockFace
        if (face !in CARDINAL_FACES) return

        // ⚠️ **只有"抽取"那一面才开界面** —— 复刻 Pipez 的 `onPipeSideActivated`：
        // ```java
        // if (tileEntity instanceof EnergyPipeTileEntity && isExtracting(worldIn, pos, direction))
        //     PipeContainerProvider.openGui(...)
        // ```
        // 理由（用户原话）：**管道的性能是由抽取端口决定的** —— 升级模块、过滤、
        // 红石都挂在"抽取"这个端点上，非抽取面没有可配的东西。
        if (faceMode(face) != FaceMode.EXTRACT) {
            event.player.sendActionBar(
                net.kyori.adventure.text.Component.text(
                    "这一面不是「抽取」端，无法配置（潜行 + 扳手右键切换模式）",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY
                )
            )
            return
        }

        event.isCancelled = true
        ConduitGui.open(event.player, this, face)
    }

    /**
     * 手上这个是不是"该自己处理右键、不要弹界面"的物品。
     *
     * 只包括**升级模块**（插槽）和**管道**（放置）——
     * 扳手不在这里：扳手是潜行手势，不潜行时应该弹界面。
     */
    private fun isPlacementItem(stack: ItemStack): Boolean =
        RebarItem.fromStack(stack, UpgradeItem::class.java) != null ||
            RebarItem.fromStack(stack, ConduitItem::class.java) != null

    /** 只有能量管才需要电力节点；流体管建了也没用，还会白占一份 PDC。 */
    private fun ensureNodeIfEnergy() {
        if (pipeType.category == PipeType.Category.ENERGY) ensureNode()
    }

    /**
     * 自愈：把旧版本留下的实体底块改成不可见的 `STRUCTURE_VOID`。
     *
     * 0.1.0 / 0.1.1 注册管段时底块用的是 `Material.GRAY_CONCRETE`。
     * **方块材质是写在世界数据里的，升级插件不会改变已经放下的方块** ——
     * 所以那些旧管段在世界里至今仍是实打实的灰色混凝土块，
     * 看起来就是"外面一直是灰色混凝土"，跟显示实体没关系。
     *
     * 这里在加载时直接把类型改掉。改 `block.type` 是 Rebar 认可的操作
     * （`FacadeRebarBlock` 就是用同样的方式做伪装的）。
     */
    private fun healLegacyBlockType() {
        if (block.type == Material.STRUCTURE_VOID) return
        runCatching { block.type = Material.STRUCTURE_VOID }
    }

    /**
     * 让 Rebar 的 `EntityHolderRebarBlock.holders` 登记本方块。
     *
     * ## 这是个 Rebar 地雷，必须处理
     *
     * `EntityHolderRebarBlock` 内部有一张 `holders` 表，是 `getOrPut` **惰性初始化**的：
     * 只有"读过 `heldEntities`"或"反序列化"时才会出现本方块。
     *
     * 而序列化那边是：
     * ```kotlin
     * holders[block]?.let { event.pdc.set(entityKey, entityType, it) }   // null 就什么都不写
     * ```
     * 反序列化那边是：
     * ```kotlin
     * holders[block] = event.pdc.get(entityKey, entityType)?.toMutableMap()
     *     ?: error("Held entities not found for ${block.key}")           // 没写就炸
     * ```
     *
     * 所以**一个展示实体都不持有、又从不读 `heldEntities` 的方块，
     * 存盘时不会写 `entity_holder_entity_uuids`，下次加载直接抛
     * `IllegalStateException: Held entities not found for pipez:conduit`**。
     *
     * 我们的管段恰好就是这种：`ElectricRebarBlock` 继承了 `EntityHolderRebarBlock`，
     * 但我们只用 `addElectricNode`（裸节点、无实体），从不产生端口展示实体。
     *
     * 修法就是这里主动读一次 —— 读一下就会把**空表**登记进去，存盘时正常写入。
     * 调用 `heldEntities.size` 而不是直接写 `heldEntities`，是为了让这次读取有明确用途。
     */
    private fun ensureHolderRegistered(): Int = heldEntities.size

    override fun write(pdc: PersistentDataContainer) {
        super.write(pdc)

        // 只在非默认值时写，普通管段的 PDC 里没有自定义字段
        if (!faceModesAreDefault()) {
            pdc.set(PipezKeys.FACE_MODES, PersistentDataType.STRING, encodeFaceModes())
        }

        // ⚠️ **同时**写一份旧禁用掩码 —— 不是冗余，是**降级通道**：
        // 万一玩家回滚到旧版本插件，旧版本只会读这个键，没有它就会把
        // 被设成 NONE 的面当成"能连"（旧版没有模式概念，只有开/关）。
        // 只写 NONE 的位，EXTRACT / INSERT 都是"连"，旧版读到就不禁用。
        val disabled = FaceMode.disabledMaskOf(
            CARDINAL_FACES.associateWith { faceModes[it] ?: FaceMode.DEFAULT }
        )
        if (disabled != 0) {
            pdc.set(PipezKeys.DISABLED_FACES, PersistentDataType.BYTE, disabled.toByte())
        }

        // 每面插着的升级模块（物品本体）
        if (sideUpgrades.isNotEmpty()) {
            val encoded = sideUpgrades.entries.joinToString(";") { (face, stack) ->
                val bytes = java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes())
                "${face.name}=$bytes"
            }
            pdc.set(PipezKeys.SIDE_UPGRADES, PersistentDataType.STRING, encoded)
        }

        // 每面的三种模式（只在非全默认时写）
        encodeOrdinalRow(RedstoneMode.DEFAULT.ordinal) { (sideRedstone[it] ?: RedstoneMode.DEFAULT).ordinal }
            ?.let { pdc.set(PipezKeys.SIDE_REDSTONE, PersistentDataType.STRING, it) }
        encodeOrdinalRow(Distribution.DEFAULT.ordinal) { (sideDistribution[it] ?: Distribution.DEFAULT).ordinal }
            ?.let { pdc.set(PipezKeys.SIDE_DISTRIBUTION, PersistentDataType.STRING, it) }
        encodeOrdinalRow(FilterMode.DEFAULT.ordinal) { (sideFilterMode[it] ?: FilterMode.DEFAULT).ordinal }
            ?.let { pdc.set(PipezKeys.SIDE_FILTER_MODE, PersistentDataType.STRING, it) }

        // 每面过滤器列表（只在非空时写）
        val filterEntries = sideFilters.entries.filter { it.value.isNotEmpty() }
        if (filterEntries.isNotEmpty()) {
            val encoded = filterEntries.joinToString(";") { (face, list) ->
                val items = list.joinToString(",") { stack ->
                    java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes())
                }
                "${face.name}=$items"
            }
            pdc.set(PipezKeys.SIDE_FILTERS, PersistentDataType.STRING, encoded)
        }

        // ⚠️ 旧的"整管档位"键：老版本读它。现在档位是每面一个模块，
        // 这里写"所有面里最高的那一档"，让回滚到旧版本的玩家至少不会掉档。
        val bestTier = CARDINAL_FACES.map { tierOf(it) }.maxByOrNull { it.ordinal } ?: ConduitTier.BASIC
        if (bestTier != ConduitTier.BASIC) {
            pdc.set(PipezKeys.TIER, PersistentDataType.BYTE, bestTier.ordinal.toByte())
        }
    }

    /**
     * 方块被拆时把流体点摘掉。
     *
     * 电力节点由 Rebar 自己清理（`ElectricRebarBlock` 的伴生对象监听破坏事件），
     * 但流体点是我们手动建的，得自己收。
     */
    override fun onPostBlockBreak(context: BlockBreakContext) {
        ConduitFluid.detach(this)
    }

    // ---------------------------------------------------------------- 形状

    /**
     * 本管段的形状掩码，由六个邻居现推。
     *
     * 邻居区块未加载时 `BlockStorage.get` 返回 null，该方向会被判为"不通"——
     * 这是可接受的降级：区块加载后 `postLoad` 会重新 markAround 并修正。
     */
    fun shapeMask(): Int = ConduitShape.mask { face -> connectsTo(face.toBlockFace()) }

    fun shape(): ConduitShape.Shape = ConduitShape.classify(shapeMask())

    /**
     * 本方向是否连通。
     *
     * ## 四条规则
     *
     * 1. 被扳手禁用的面 → 不通。
     * 2. 邻居是**同类型**的管段 → 通。**不同类型的管道之间永远不通**
     *    （能量管和流体管并排铺也不会连；两种网络语义不同 —— 瓦特 / mB·s⁻¹，
     *    混在一起既没法配也没法调）。
     * 3. 邻居是**机器** → 只有那一面**真有对应端口**才通：
     *    - 能量管：那一面得有 `ElectricPortEntity`（走 [ConduitLinker.portLoadState]）
     *    - 流体管：那一面得有流体口（走 [ConduitFluid] 的 `machinePointAt`）
     * 4. 邻居是其它东西（`ItemEndpoint`、原版方块、别的附属的机器）→ 不通。
     *
     * ## 为什么机器这一条要看"端口在不在"
     *
     * 之前写的是 `neighbor is ElectricRebarBlock` —— **永远为真**。
     * 于是"一台电口开在北面的机器，南面贴着管道"也会让管道画一条胳膊过去，
     * 连又连不上（端口不在这面），就成了用户看到的"看着连了其实没连"。
     *
     * 现在改成"必须真有端口"。⚠️ 但**不能把'端口还在加载'当成'没有端口'** ——
     * 那会让管道在区块加载时先断一下再连上。所以这里对 PENDING 也返回 true
     * （见 [machineLinkState]），只对**确定没有**返回 false。
     */
    private fun connectsTo(face: BlockFace): Boolean {
        if (isDisabled(face)) return false
        // 红石模式：不满足条件的面**直接不连**（复刻 Pipez 的 shouldWork 把关）
        if (!redstoneAllows(face)) return false
        val neighbor = rebarAt(block.getRelative(face)) ?: return false
        if (neighbor is ConduitBlock) {
            // ⚠️ 还要看**对面那一面是不是断路** —— 否则断路是"单边"的：
            // 我这面正常、邻居那面断路时，我仍然以为连着，于是管身一路画过去，
            // 用户设了断路却看不到任何断开（红箭头那一处）。
            // 只查 `isDisabled`（不递归调 connectsTo），所以不会无限递归。
            return neighbor.pipeType == pipeType && !neighbor.isDisabled(face.oppositeFace)
        }
        // ⚠️ 机器侧还要过一道 [MachinePortGuard]：**同一张管网里一台机器只接一个端口**。
        // 否则把机器的输出口和输入口都接进来 = 短接 = 凭空生电（用户实测）。
        return machineLinkState(neighbor, face) != LinkState.ABSENT &&
            MachinePortGuard.allows(this, face)
    }

    /** 邻居（机器）在这一面能不能接。"能否确定"决定渲染与连线怎么处理。 */
    private enum class LinkState {
        /** 端口在，可以连。 */
        PRESENT,

        /** 名字在，实体还没加载 —— 先当连上（渲染稳定），网络侧保持现状。 */
        PENDING,

        /** 确定没有对应端口 —— 不连、也不画胳膊。 */
        ABSENT
    }

    /**
     * 判断 [neighbor] 在 [face] 这一面的连接状态。
     *
     * 非机器（`ConduitBlock` / `ItemEndpoint` / 普通方块）一律 [LinkState.ABSENT] ——
     * 这条就是"管道不与物品端点、也不与其它东西自动连接"的实现。
     *
     * 参数用 [Any]? 而不是 `RebarBlock`：`ElectricRebarBlock` / `FluidRebarBlock`
     * 在 Rebar 里是**接口**，Kotlin 侧和 `RebarBlock` 类没有继承关系，
     * 这里只做 `is` 判定，用 `Any?` 最省事（null = 空气，直接 ABSENT）。
     */
    private fun machineLinkState(neighbor: Any?, face: BlockFace): LinkState =
        when (pipeType.category) {
            PipeType.Category.ENERGY ->
                if (neighbor !is ElectricRebarBlock) {
                    LinkState.ABSENT
                } else {
                    when (ConduitLinker.portLoadState(neighbor, face.oppositeFace)) {
                        ConduitLinker.PortState.PRESENT -> LinkState.PRESENT
                        ConduitLinker.PortState.PENDING -> LinkState.PENDING
                        ConduitLinker.PortState.ABSENT -> LinkState.ABSENT
                    }
                }

            PipeType.Category.FLUID ->
                if (neighbor !is FluidRebarBlock) {
                    LinkState.ABSENT
                } else if (ConduitFluid.hasPointFacing(neighbor, face.oppositeFace)) {
                    LinkState.PRESENT
                } else {
                    LinkState.ABSENT
                }

            // 物品管还没做 → 不存在任何"该连的机器"，一律不连
            PipeType.Category.ITEM -> LinkState.ABSENT
        }

    /** 供邻段查询：本方块在 [face] 方向有没有连接。 */
    fun isConnectedOn(face: BlockFace): Boolean = connectsTo(face)

    /**
     * 本格贴着 [machine]、且那一面**真有端口**的那些面（按 [CARDINAL_FACES] 顺序）。
     *
     * 供 [MachinePortGuard] 判断"这台机器在这张管网里该由哪个端口出面"。
     */
    fun portFacesTo(machine: Block): List<BlockFace> {
        val result = ArrayList<BlockFace>(2)
        for (f in CARDINAL_FACES) {
            val n = block.getRelative(f)
            if (n.x != machine.x || n.y != machine.y || n.z != machine.z) continue
            if (isDisabled(f)) continue
            val neighbor = rebarAt(n) ?: continue
            if (neighbor is ConduitBlock) continue
            if (machineLinkState(neighbor, f) != LinkState.ABSENT) result += f
        }
        return result
    }

    /** 本方块连了几个方向。 */
    fun connectionCount(): Int = CARDINAL_FACES.count { connectsTo(it) }

    // ---------------------------------------------------------------- 渲染

    /**
     * **关掉 Rebar 的 packet 方块贴图**，改用真实 [org.bukkit.entity.ItemDisplay] 画线。
     *
     * 抄 Pylon 的 `CargoDuct`。为什么必须这样：
     * `BlockTextureEntity` 那条路**要有资源包才有造型**，没有资源包时它只会渲染成一个
     * 被实体方块遮挡、亮度为 0 的方块 —— 看起来就是一坨黑混凝土。
     * （Pylon 源码里的原话：occlude the display entity and cause it to render with brightness 0）
     *
     * Pylon 的做法是彻底不用它：用 `ItemDisplayBuilder` + `LineBuilder`
     * 在两个方块中心之间画一条细线，**线条本身就是管道的形状**，不需要资源包。
     */
    override var disableBlockTextureEntity = true

    /**
     * 形状变了才重建显示实体。
     *
     * 显示实体是**真实实体**（要 spawn / remove），不能每次 flush 都重建 ——
     * 用 [lastDisplayMask] 记住上次的形状掩码。
     */
    private fun refreshDisplaysIfShapeChanged(mask: Int) {
        if (mask == lastDisplayMask) return
        lastDisplayMask = mask
        rebuildDisplays()
    }

    private fun rebuildDisplays() {
        clearDisplays()
        val spawn = block.location.toCenterLocation()

        // 0 通：一个黑色正方体 + 三个方向的黄色箍（"还没接上"的标志）
        if (shapeMask() == 0) {
            spawnIsolatedCube(spawn)
            return
        }

        // 直线段（含**悬空管的两端格**）：整条直线合并成一个显示，由起点负责画。
        // 不合并的话一段 16 格的直管要 64 个展示实体；合并后只要 3 + 16 个。
        //
        // ⚠️ 这里用 [ConduitShape.runAxis]（允许度数 1 的端点）而不是"恰好两个连接"。
        // 悬空 2 通的两格各自只连一格，若按度数 2 判就不是直线 → 各自画臂 →
        // 视觉上两侧各多出一截断臂（用户看到的 `臂心臂心臂`）。
        //
        // 方向再归一成轴的**规范正方向**（EAST / UP / SOUTH），保证"起点"永远是
        // 坐标最小的那一端 —— 否则末端格会往反方向画出一条长度为负的管身。
        val axis = ConduitShape.runAxis(shapeMask())
        if (axis != null) {
            val axisDir = canonicalDirectionFor(axis)
            if (isRunStart(axisDir)) spawnRun(spawn, axisDir)
            return
        }

        // 拐弯 / 三通 / 十字 / 端口：**每个连接方向各画一条臂**。
        //
        // ⚠️ 这里刻意**不再用 `ownsSegment` 决定"由谁画"**。
        // 之前是"坐标较小的一端负责画"，属于**跨方块协调** —— 一旦某侧邻居的状态
        // 判断出偏差（区块未加载、方块还没注册等），就会在**反方向**凭空长出一截断臂
        // （实测：2 通多了"左"、3 通多了"上"和"左"，全是某个连接方向的正对面）。
        //
        // 现在改成**每个方块只画自己连的方向** —— "某个方向没连接却长出臂"
        // 在结构上就不可能发生了。代价是相邻两段会各画一遍（重叠部分肉眼无差，
        // 而且两侧 jitter 不同，不会 Z-fighting）。
        for (face in CARDINAL_FACES) {
            if (!connectsTo(face)) continue
            spawnSegment(spawn, face)
        }

        // 中心补一个方块：两条垂直臂正交相交时，内角天然是 90° 的**凹口**，
        // 光靠臂本身填不上。方块要够大（> 管身 × √2）才能真正把凹角盖住。
        //
        // ⚠️ 只在"两条臂正交"（拐弯 / 接头）时才补。如果是**端口**（度数 1），
        // 唯一的臂已经穿满整个方块，中心再放方块只会让管口鼓出一个疙瘩。
        if (connectionCount() >= 2) spawnHub(spawn)
    }

    /**
     * 拐弯 / 接头的中心方块（填凹角）。
     *
     * ⚠️ **必须和管身同尺寸**（`bodyThickness`），不能放大。
     *
     * 原版 Pipez 的拐角就是"一根管直穿到底，另一根在它侧面靠过来，接缝处套一圈彩箍"——
     * 拐角处的管子**不会变粗**。之前这里放了一个 0.46 的大方块（> 管身 × √2），
     * 比管身胖出一大圈，视觉上就像"拐弯处鼓了个疙瘩"，和原版完全不是一个东西。
     *
     * 那凹角怎么办？**凹角本来就不该被填满** —— 两根方管正交时内角天然有一个小缺口，
     * 原版 Pipez 也有，只是彩箍在拐角处套了一圈，把视觉重心带过去了。
     * 所以这里只要保证中心有实心体把两根臂连起来（不露贯穿的缝）就够了。
     */
    private fun spawnHub(spawn: Location) {
        // 比管身略小一点点（0.002），保证它的侧面不会和相邻臂的侧面共面
        val size = PipezConfig.bodyThickness - 0.002f
        val half = size / 2.0
        // jitter 用第 7 档，避开 6 个方向臂的 0..5 档，防止和臂的侧面共面
        val jitter = JITTER_STEP * CARDINAL_FACES.size
        addEntity(
            "${DISPLAY_PREFIX}hub",
            box(spawn, Vector(0.0, -half, 0.0), Vector(0.0, half, 0.0),
                size - jitter, size - jitter, pipeType.bodyMaterial, "hub")
        )
    }

    /**
     * 本方块是不是它所在直线段的**起点**（线段上坐标最小的那一端）。
     *
     * 只有起点负责画整条线，其余方块什么都不画 —— 这就是贪心合并的关键。
     *
     * 判据：沿 [axis] 的**负方向**再往前一格，如果不是"同一条轴上的直线管"，
     * 本格就是起点。因为 [axis] 已经归一成轴的规范正方向（EAST / UP / SOUTH），
     * 所以整条轴上所有格子对"谁是起点"的判断天然一致。
     */
    private fun isRunStart(axis: BlockFace): Boolean {
        // ⚠️ 本格朝反方向**没连着**（断路 / 对面断路 / 空气）→ 本格就是这条 run 的起点。
        //
        // 少这一句的后果：反方向那个"想连过来"的邻居仍然让我觉得"我前面还有管子"，
        // 于是我以为自己不是起点、**什么都不画** —— 断口之后的那一段整段消失。
        if (!connectsTo(axis.oppositeFace)) return true
        val prev = rebarAt(block.getRelative(axis.oppositeFace)) as? ConduitBlock ?: return true
        if (!prev.isConnectedOn(axis)) return true
        // 邻居必须也属于同一条轴的直线（拐弯 / 三通都不算延续）
        return prev.runAxis() != canonicalAxis(axis)
    }

    /**
     * 本方块所处的直线轴；不是直线返回 null。
     *
     * 直接用纯函数 [ConduitShape.runAxis]（有单测），把 Bukkit 侧的连通性喂给它。
     */
    fun runAxis(): Axis? = ConduitShape.runAxis(shapeMask())

    /** 某个正方向属于哪条轴。 */
    private fun canonicalAxis(face: BlockFace): Axis = when (face) {
        BlockFace.EAST, BlockFace.WEST -> Axis.X
        BlockFace.UP, BlockFace.DOWN -> Axis.Y
        else -> Axis.Z
    }

    /**
     * 直线段某一端，管身该从**本格中心**往外伸多远。
     *
     * - 对面是空气 → 伸到表面再加一点（[END_PROTRUSION]），形成可见的"管口"
     * - 对面是机器 → **齐平停在表面**（`FACE_REACH`），像插头插进去，不穿进机器
     * - 对面是管段 → 不是"端点"，由调用方另行处理（这里给表面兜底）
     */
    private fun outwardReach(neighbor: io.github.pylonmc.rebar.block.RebarBlock?): Double =
        if (neighbor == null) FACE_REACH + END_PROTRUSION else FACE_REACH

    /**
     * 把某条轴的**规范正方向**转回 Bukkit 的 [BlockFace]：X→EAST、Y→UP、Z→SOUTH。
     *
     * 一条轴上的所有格子都用同一个方向判"谁是起点"，不会因为某个格子的
     * 连接方向恰好朝反方向而误判（那个误判会产生一条长度为负的管身 =
     * 用户看到的"两侧多出两个臂"）。
     */
    private fun canonicalDirectionFor(axis: Axis): BlockFace = when (axis) {
        Axis.X -> BlockFace.EAST
        Axis.Y -> BlockFace.UP
        Axis.Z -> BlockFace.SOUTH
    }

    /**
     * 画一整条直线段（从本方块一直画到这条直线的另一端）。
     *
     * 这是贪心合并的核心：一整条直线只用一个管身 + 两条彩条，彩箍按格重复。
     *
     * 邻居算"延续"的判据：**同一条轴上的直线管**。端点格（度数 1）也要并进来 ——
     * 否则那一截会缺，或者被它自己用"画臂"的方式重画一遍，两侧各多出一截断臂。
     */
    private fun spawnRun(spawn: Location, face: BlockFace) {
        val wanted = canonicalAxis(face)
        var endBlock: ConduitBlock = this
        var guard = 0
        while (guard++ < MAX_RUN) {
            // ⚠️ **当前格朝 face 那一面必须真的连着**才能继续往前并。
            //
            // 少这一句的后果：中间某一格设了断路，整条 run 仍然"跨过"它一路画到底
            // —— 用户设了断路却完全看不出断开（红箭头那一处）。
            if (!endBlock.connectsTo(face)) break
            val next = rebarAt(endBlock.block.getRelative(face)) as? ConduitBlock ?: break
            if (!next.isConnectedOn(face.oppositeFace)) break
            if (next.runAxis() != wanted) break
            endBlock = next
        }

        // 线段两端是什么：同类管段 / 机器 / 另一种管 / 空气。
        // - **同类**管段 → 只重叠一点点（对面那段会覆盖）
        // - 机器 → 齐平停在方块表面（像插头插进去，不穿模）
        // - 另一种管 → 也齐平停在表面（不糊在一起）
        // - 空气 → 比表面多伸一点，形成可见管口
        //
        // ⚠️ 不能用"那一侧有没有连接"来判断 —— 接到**机器**时 connectsTo 也是 true，
        // 结果会把管道多伸出去近一格，在机器里长出一截死路。
        //
        // ⚠️ 也不能只写 `is ConduitBlock` —— 那会把**另一种颜色的管**也算成同类，
        // 于是管道伸进对方一整格。必须同一个类型才算"接上"。
        val before = rebarAt(block.getRelative(face.oppositeFace))
        val beyond = rebarAt(endBlock.block.getRelative(face))
        val beyondConduit = beyond as? ConduitBlock
        val beyondIsSamePipe = beyondConduit != null && beyondConduit.pipeType == pipeType
        // 对面那根同类管**愿不愿意连回来** —— 它要是把这一面设了断路，我们就别伸过去。
        val beyondConnectsBack = beyondConduit != null &&
            beyondConduit.pipeType == pipeType &&
            beyondConduit.isConnectedOn(face.oppositeFace)

        // ⚠️ 起点格朝反方向设了**断路** → 管身就从中心开始，不能再往外伸半格。
        // 否则断路那一段仍然"顶着邻居"，看起来根本没断开。
        val startOut = if (isDisabled(face.oppositeFace)) 0.0 else outwardReach(before)
        // `runLength` 是"到最后一个直线方块中心"的距离。
        // - 对面是同类管段（下一格是接头 / 拐弯）：再往前**一整格再加一点**（`1.0 + [OVERLAP]`），
        //   够到那个接头方块的中心，保证拐角交叠；
        // - 对面是机器 / 另一种管 → 齐平停在表面（`FACE_REACH`）；
        // - 对面是空气 → 表面外一点（`FACE_REACH + [END_PROTRUSION]`），形成管口。
        val endOut = when {
            // 末端格朝外设了断路 → 管身停在**末端格中心**，和对面留出半格空隙
            endBlock.isDisabled(face) -> 0.0
            beyondConnectsBack -> 1.0 + OVERLAP
            else -> outwardReach(beyond)
        }

        val axis = face.direction.clone().normalize()
        val runLength = endBlock.block.location.toCenterLocation().toVector()
            .subtract(spawn.toVector()).length()

        val bodyFrom = axis.clone().multiply(-startOut)
        val bodyTo = axis.clone().multiply(runLength + endOut)

        // 管身和彩条共用同一组端点（彩条不另算，见 spawnRod 的注释）
        spawnRod(spawn, bodyName(face), bodyFrom, bodyTo, jitterFor(face))

        // 彩箍：只在**真正的格边界**放，而且跳过两端。
        //
        // ⚠️ 之前是 `offset = 0.5; while (offset <= runLength)` —— 2 通时 runLength = 1.0，
        // 于是正好在正中间（0.5）放了一道箍，把一整根管从视觉上切成两截。
        // 现在改成：**只有当那一格边界两侧都是管身**（即不是 run 的端点）时才放，
        // 所以 2 通直管中间不会再出现那道箍。
        // 彩箍同样是纯装饰，而且**每格一个**（长直管上也一样）——
        // 关掉它对实体数的贡献最大（见 config.yml 的 render.bands）。
        val bandT = PipezConfig.bandThickness - jitterFor(face)
        val bandHalf = PipezConfig.bandLength * 0.5
        var offset = 1.0
        while (PipezConfig.renderBands && offset < runLength) {
            val center = axis.clone().multiply(offset)
            addEntity(
                "${DISPLAY_PREFIX}band:${face.name}:$offset",
                box(spawn, center.clone().subtract(axis.clone().multiply(bandHalf)),
                    center.clone().add(axis.clone().multiply(bandHalf)),
                    bandT, bandT, pipeType.stripeMaterial, "band")
            )
            offset += 1.0
        }

        // ---------------------------------------------------------------
        // 抽取端的大黑板：run 的**两端**若接着机器且是"抽取"，各贴一块
        // ---------------------------------------------------------------
        //
        // run 是"整条直线由起点格统一画"，所以机器端的板也必须在这里画 ——
        // 起点格的渲染里没有"另一端"的臂，`spawnSegment` 不会被调到那里。
        val backFace = face.oppositeFace
        if (faceMode(backFace) == FaceMode.EXTRACT && isMachineFacing(backFace)) {
            // 起点侧：贴在**起点格**朝 backFace 的那一面
            spawnExtractPlate(spawn, backFace, FACE_REACH - EXTRACT_PLATE_THICK / 2.0)
        }
        // ⚠️ 末端侧要问的是**末端格**（`endBlock`）的模式与邻居，不是起点格 ——
        // 起点格朝 face 的那一面在整条 run 的另一头，拿它判会画错位置、也会漏画。
        if (endBlock.faceMode(face) == FaceMode.EXTRACT && endBlock.isMachineFacing(face)) {
            // 末端侧：贴在**末端格**朝 face 的那一面
            spawnExtractPlate(spawn, face, runLength + FACE_REACH - EXTRACT_PLATE_THICK / 2.0)
        }
    }

    /**
     * [face] 那一侧是不是**真的连着**一台机器。
     *
     * 直接复用 [connectsTo]（它已经含了 断路 / 红石 / 端口是否存在 / 防自短接 四道判据）
     * 再排除同类管 —— 这样"画不画抽取黑板"和"画不画胳膊"永远一致：
     * 不会出现"有黑板却没有胳膊"这种自相矛盾的画面。
     */
    private fun isMachineFacing(face: BlockFace): Boolean {
        if (face !in CARDINAL_FACES) return false
        val neighbor = rebarAt(block.getRelative(face)) ?: return false
        if (neighbor is ConduitBlock) return false
        return connectsTo(face)
    }

    /**
     * 画"抽取端"那块**大黑板**。
     *
     * 复刻 Pipez 的 `SHAPE_EXTRACT_*`：`Block.box(4,15,4,12,16,12)` ——
     * 横截面 **8/16 = 0.5**、厚 **1/16 = 0.0625**，贴在方块**外表面**上
     * （15/16 ~ 16/16，正好齐平外沿）。
     *
     * [distance] 是板**中心**沿 [face] 正方向距本格中心的距离
     * （所以它永远是正的：`FACE_REACH - 厚/2`）。
     */
    private fun spawnExtractPlate(spawn: Location, face: BlockFace, distance: Double) {
        val axis = face.direction.clone()
        val half = EXTRACT_PLATE_HALF
        val thick = EXTRACT_PLATE_THICK
        val center = axis.clone().multiply(distance)
        addEntity(
            "${DISPLAY_PREFIX}extract:${face.name}",
            box(
                spawn,
                center.clone().subtract(axis.clone().multiply(thick / 2.0)),
                center.clone().add(axis.clone().multiply(thick / 2.0)),
                (half * 2).toFloat(),
                (half * 2).toFloat(),
                EXTRACT_PLATE_MATERIAL,
                "extract"
            )
        )
    }

    private fun clearDisplays() {
        for (name in heldEntities.keys.toList()) {
            if (!name.startsWith(DISPLAY_PREFIX)) continue
            getHeldEntity(name)?.remove()
            heldEntities.remove(name)
        }
        // ⚠️ 这里**不**调 [purgeOrphanedDisplays]（见那里的说明）：
        // 它要做一次 `getNearbyEntities` 实体查询，放进每次重建里会在
        // 大型管网上变成"每格每次重建一次全实体扫描"。改到 [postLoad] 做。
    }

    /**
     * 清掉"孤儿展示实体"。
     *
     * 背景：`EntityHolderRebarBlock.addEntity` 会往实体的 PDC 里写一个 `blockKey`
     * （指向拥有它的方块）。如果方块换过造型、或者**插件热更过版本**
     * （旧版创建的实体不在新版的 `heldEntities` 里），那些实体就再也没人认领 ——
     * 它们会一直留在世界里，表现为"凭空多出来的短壁 / 短臂"。
     *
     * 扫一下方块附近的 `ItemDisplay`：**PDC 里 blockKey 指向本方块、
     * 但不在 heldEntities 里的，一律删掉。**
     *
     * ⚠️ **只在 [postLoad] 调一次**，不要挂进 [clearDisplays]。
     * 原因：这里的 `getNearbyEntities(1.5³)` 是**服务端实体查询**，
     * 代价随附近实体数增长。管网一大（用户实测：一片几百格的锯齿网），
     * 每次重建都扫一遍会直接把 TPS 拖死。而它要解决的只是
     * "插件热更后旧实体没人认领"，加载时扫一次就够了。
     */
    private fun purgeOrphanedDisplays() {
        val owned = heldEntities.values.toHashSet()
        val center = block.location.toCenterLocation()
        block.world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter { it.uniqueId !in owned }
            .filter { display ->
                runCatching {
                    display.persistentDataContainer.get(
                        EntityHolderRebarBlock.blockKey,
                        RebarSerializers.BLOCK_POSITION
                    ) == block.position
                }.getOrDefault(false)
            }
            .forEach { it.remove() }
    }

    /**
     * 画一段管道（含管身、两条全宽彩条、彩箍）。
     *
     * 两端的伸出量**分别算**：
     * - 那一侧还有连接 → 只重叠 [OVERLAP]（避免和邻段叠出厚度）
     * - 那一侧是尽头 → 伸到方块表面再多一点（[FACE_REACH] + [END_PROTRUSION]），形成"管口"
     *
     * 这样 1~6 个方向的接头都能接上：每段都穿过自己方块的中心，
     * 多条段线在中心自然交汇，不会出现断口。
     */
    /**
     * 画一段管道（含管身、两条全宽彩条、彩箍）。**只用于拐弯 / 三通 / 十字**，直线段走 [spawnRun]。
     *
     * 关键：这一端往回伸多少，取决于**本方块的度数**，不是"反方向有没有连接"。
     *
     * - **度数 1**（就一个连接）→ 这是管子端口，要往回伸到方块表面（+ [END_PROTRUSION]）
     * - **度数 ≥ 2**（拐弯 / 接头）→ 只从中心开始（[OVERLAP]）
     *
     * ⚠️ 之前写成"反方向没连接就当作端口"，结果拐弯块的每条臂都会往反方向长出一截
     * **死路通**（一个拐弯凭空多两根），接头也会多出若干根。
     */
    private fun spawnSegment(spawn: Location, face: BlockFace) {
        val neighborBlock = block.getRelative(face)
        val target = neighborBlock.location.toCenterLocation()
        val offset = target.toVector().subtract(spawn.toVector())   // 指向邻格中心，长度 1
        val axis = offset.clone().normalize()

        // 本方块是不是"端口"（只有一个连接）
        val startIsEnd = connectionCount() <= 1
        // 对面是不是**同类**管段（同类才互相"接上"，才需要交叠彩条）
        val neighbor = rebarAt(neighborBlock)
        val neighborIsConduit = neighbor is ConduitBlock && neighbor.pipeType == pipeType
        // 对面是不是**真有可接端口的机器**。
        //
        // ⚠️ 这里必须和 [connectsTo] 用**同一个判据**，不能写 `neighbor != null`。
        // 之前写成"非管段且非空"，于是**另一种颜色的管段**也被当成机器 ——
        // 能量管的臂就这样平头怼在流体管脸上，看着像"两根管连一起了"
        // （用户绿箭头："它怎么连一起了"）。
        // 现在只有"那一面真有对应端口"的机器才算机器，另一种管段一律按**空气**处理
        // （臂会带出可见管口，且和对方保持本格的边界，不会糊成一体）。
        val neighborIsMachine = !neighborIsConduit && machineLinkState(neighbor, face) != LinkState.ABSENT
        // 对面是**另一种颜色**的管段。
        //
        // 这种邻居既不能"接上"（类型不同），也不该像空气那样把管口伸出去
        // —— 伸出去会戳进对方方块，看起来两者贴在一起。所以它单独一档：
        // **齐平停在本格表面**，既不糊在一起、也不越界。
        val neighborIsOtherPipe = neighbor is ConduitBlock && neighbor.pipeType != pipeType

        // ---------------------------------------------------------------
        // 臂的端点
        // ---------------------------------------------------------------
        //
        // 参考 Pylon `CargoDuct.createDuctDisplay`：一条臂 = 从远端拉到某一端的中心，
        // 两端各加 `extraLength`。关键是**两条正交的臂必须在中心区充分交叠**，
        // 这样拐角处才严丝合缝。
        //
        // ⚠️ **端点一律以"本格中心"为原点算**，不要以邻格中心为原点再叠加。
        //
        // 之前的写法是 `bodyTo = offset + axis * farOut` —— `offset` 是"到邻格中心"
        // 的向量（长度 1.0）。对面是机器时 `farOut = 0.54`，于是臂端落到 1.54，
        // 也就是**捅进机器方块 0.54 格**（用户："管道给目标贯穿了"）。
        // 正确做法：以本格中心为原点，机器侧只伸到**本格朝外的那一面**
        // （`FACE_REACH` = 0.5），**齐平收口**、正好插在机器表面，不穿进去。
        //
        // 本方块朝 [face] 画一条臂：
        // - **近端**（-axis，背对 face 那侧）：端口 → 伸到方块表面（管口）；
        //   接头/拐弯 → 只到中心（中心方块会把它连起来）。
        // - **远端**（+axis，朝 face 那侧）：
        //   对面是同类管段 → 伸到**邻格中心**再越过一点（[OVERLAP]），保证拐角交叠；
        //   对面是机器 → 齐平停在**本方块表面**（`FACE_REACH`），像插头插进去；
        //   对面是**另一种颜色的管** → 同样齐平停在表面，不戳进对方；
        //   对面是空气 → 比表面多伸一点（[END_PROTRUSION]），形成可见的管口。
        val nearOut = if (startIsEnd) FACE_REACH + END_PROTRUSION else OVERLAP
        val farOut = when {
            neighborIsConduit -> 1.0 + OVERLAP
            neighborIsMachine || neighborIsOtherPipe -> FACE_REACH
            else -> FACE_REACH + END_PROTRUSION
        }

        val bodyFrom = axis.clone().multiply(-nearOut)
        val bodyTo = axis.clone().multiply(farOut)

        // ---------------------------------------------------------------
        // 彩条的端点 —— 接头端只伸到"刚好没入正交臂彩条"
        // ---------------------------------------------------------------
        //
        // 拐角块里两根正交臂交于本格中心。正交臂的**外轮廓**（含彩条凸出）
        // 在离中心 `stripeFullWidth/2` 处 —— 这就是本臂彩条头该停的地方：
        // **再往里一点就露黑缝，再往外一点就戳出对方轮廓**（用户："突出太多了"）。
        //
        // ⚠️ 之前用 `bodyThickness/2 + stripeThickness/2`（0.215）偏大：
        // 那算的是"对方彩条的**内**侧面"的位置，而该停的是"对方彩条的**外**侧面"
        // `stripeFullWidth/2`（0.19）。两者的差就是用户看到的那一小截凸出。
        val into = PipezConfig.stripeIntoJoint
        val jointReach = PipezConfig.stripeFullWidth / 2.0 + into

        // ------------------------------------------------------------------
        // 近端（-axis，背对 face 那一侧）
        // ------------------------------------------------------------------
        //
        // ⚠️ **只有"对面真的有另一条臂"时才往后伸 `jointReach`。**
        //
        // 这里就是用户红箭头那截"断臂"的根因：一个三通块（比如 UP + DOWN + WEST），
        // 画 WEST 臂时近端无条件往后伸 0.19 —— 而"后"是**东侧**，东侧根本没有臂，
        // 于是那 0.19 的彩条**凭空戳进了东边空格**，看起来就是一截多出来的短臂。
        //
        // 正确判据：对面（`face.oppositeFace`）有臂 → 两条臂的彩条要在中心互锁 → 伸；
        // 对面没臂 → 停在管身端点（`bodyFrom`）即可，多伸出来的都是幻觉。
        val oppositeHasArm = connectsTo(face.oppositeFace)
        val stripeFrom = if (startIsEnd || !oppositeHasArm) {
            bodyFrom
        } else {
            axis.clone().multiply(-jointReach)
        }
        // 远端（+axis 方向）
        val stripeTo = if (neighborIsConduit) {
            offset.clone().add(axis.clone().multiply(jointReach))
        } else {
            bodyTo
        }

        // ⚠️ 管身和彩条共用**同一组基本端点**；彩条只在接头端伸到"盖住对方"的深度。
        spawnRod(spawn, bodyName(face), bodyFrom, bodyTo, jitterFor(face), stripeFrom, stripeTo)

        // 彩箍：段中点套一圈。粗细要**只比管身粗一点点**，且和彩条全宽错开。
        // 纯装饰，可关（见 config.yml 的 render.bands）—— 拐弯块**每条臂一个**，
        // 是锯齿状管网里最容易被忽视的实体来源。
        if (PipezConfig.renderBands) {
            val mid = offset.clone().multiply(0.5)
            val half = axis.clone().multiply(PipezConfig.bandLength * 0.5)
            val bandT = PipezConfig.bandThickness - jitterFor(face)
            addEntity(
                bandName(face),
                box(spawn, mid.clone().subtract(half), mid.clone().add(half),
                    bandT, bandT, pipeType.stripeMaterial, "band")
            )
        }

        // 方向指示：只在"这一面接着机器"时画。
        // 管段之间没有方向（同类管互通是双向的），画了只会让画面变乱。
        if (neighborIsMachine) spawnExtractPlateIfExtract(spawn, face)
    }

    /**
     * 一个很小的错开量，让**不同方向**的几何不共面。
     * 为什么必须有：管段在方块中心会互相重叠。如果两个方向的几何尺寸完全一样，
     * 它们的侧面就**完全共面** → 渲染闪烁（Z-fighting）。Pylon 的 `CargoDuct` 用
     * 0.3495 / 0.3490 / 0.3485 三个近似值就是为了这个。
     *
     * ## ⚠️ 不能用 `位置 + 序号` 再取模
     *
     * 之前的写法是 `floorMod(x + y + z + face.ordinal, 3)`。它有个致命碰撞：
     * Bukkit 的 `BlockFace` 序号是 `DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5`，
     * **EAST(5) 与 NORTH(2) 相差正好 3**，模 3 之后相等 —— 拐弯块的两条臂会拿到
     * 完全一样的尺寸，侧面完全共面，看起来像管子"被折断"。
     *
     * 所以这里**直接按面的序号取唯一值**：6 个面 6 个不同尺寸，绝不碰撞。
     * 相邻方块的反向面序号本来就不同（EAST↔WEST、UP↔DOWN、SOUTH↔NORTH），
     * 所以"两段在接头处重叠"也不会共面。
     */
    private fun jitterFor(face: BlockFace): Float = face.ordinal * JITTER_STEP

    /**
     * 0 通：一个**黑色正方体** + **三个方向的黄色箍**（沿 X / Y / Z 各套一圈）。
     *
     * 这是"这根还没接上"的标志。和真正的多路接头长得完全不一样，
     * 不会像之前那样被误认成六通。
     */
    private fun spawnIsolatedCube(spawn: Location) {
        val size = PipezConfig.isolatedCubeSize
        val half = size / 2.0

        // 黑色正方体：用一根沿 Y 轴、横截面 size×size、长度 size 的盒子来画
        addEntity(
            "${DISPLAY_PREFIX}cube",
            box(spawn, Vector(0.0, -half, 0.0), Vector(0.0, half, 0.0),
                size, size, pipeType.bodyMaterial, "single")
        )

        // 三个黄色箍：每个方向套一圈，只比正方体大一点点。
        // 尺寸逐个微调（0.0007），避免三个箍的侧面互相共面 → Z-fighting。
        val ringHalf = PipezConfig.bandLength * 0.5
        listOf(
            Vector(1.0, 0.0, 0.0),
            Vector(0.0, 1.0, 0.0),
            Vector(0.0, 0.0, 1.0)
        ).forEachIndexed { index, dir ->
            val ringSize = PipezConfig.ringSize - index * 0.0007f
            addEntity(
                "${DISPLAY_PREFIX}ring$index",
                box(spawn, dir.clone().multiply(-ringHalf), dir.clone().multiply(ringHalf),
                    ringSize, ringSize, pipeType.stripeMaterial, "band")
            )
        }
    }

    /**
     * 造一根杆 = 管身 + 两条**全条长**彩条（横截面是"＋"，四个侧面都有彩条）。
     *
     * [dir] 是**管轴方向**。彩条的三维尺寸按世界坐标轴直接给：
     *
     * | 管轴 | 管身 | s0（宽扁） | s1（窄高） |
     * |---|---|---|---|
     * | X | (len, t, t)      | (len, full, thin) | (len, thin, full) |
     * | Y | (t, len, t)      | (full, len, thin) | (thin, len, full) |
     * | Z | (t, t, len)      | (full, thin, len) | (thin, full, len) |
     *
     * ⚠️ **彩条必须和管身同长**，只是在横截面上做文章。
     *
     * 之前这里传的是彩条自己的 `stripeFrom / stripeTo`，它们比管身短（或长）一截，
     * 结果彩条在各方向上的端点不一致 —— 这是"拐角那里对不上、差一点点"的**真正来源**。
     * 现在彩条和管身用**完全相同的端点**，从方块中心出发、铺满到边界，
     * 所有方向天然平齐；"接头处让头没入对方"只需要把管身端点整体往外挪。
     */
    private fun spawnRod(
        spawn: Location,
        namePrefix: String,
        bodyFrom: Vector,
        bodyTo: Vector,
        jitter: Float,
        stripeFrom: Vector? = null,
        stripeTo: Vector? = null
    ) {
        val bodyT = PipezConfig.bodyThickness - jitter
        val full = PipezConfig.stripeFullWidth - jitter
        val thin = PipezConfig.stripeThickness

        val bodyMid = bodyFrom.clone().add(bodyTo).multiply(0.5)
        val bodyLen = bodyFrom.distance(bodyTo)
        val dir = bodyTo.clone().subtract(bodyFrom).normalize()

        // 彩条端点：默认和管身共用；调用方可以给一套**更靠外**的点，
        // 让彩条在那一端伸出去（接头处「让头没入对方」）。
        //
        // 另外，无论哪种情况都给彩条的两端各加 [STRIPE_END_POKE]，
        // 让彩条端面**离开**管身端面 —— 这是消除管口 Z-fighting 的关键
        // （两片端面共面 → 渲染器交替闪烁，就是用户说的"管道头 zindex"）。
        val sFrom = stripeFrom ?: bodyFrom
        val sTo = stripeTo ?: bodyTo
        val sMid = sFrom.clone().add(sTo).multiply(0.5)
        val sLen = sFrom.distance(sTo) + STRIPE_END_POKE * 2

        addEntity(
            "$namePrefix:body",
            boxRaw(spawn, bodyMid, bodyLen, bodyT, bodyT, dir, pipeType.bodyMaterial, "body")
        )
        // 彩条是**纯装饰**（管道的"＋"形截面身份色）。每臂 3 个实体里它占 2 个，
        // 所以大管网上把它关掉能直接把实体数砍到 1/3（见 config.yml 的 render.stripes）。
        if (!PipezConfig.renderStripes) return
        addEntity(
            "$namePrefix:s0",
            boxRaw(spawn, sMid, sLen, full, thin, dir, pipeType.stripeMaterial, "stripe")
        )
        addEntity(
            "$namePrefix:s1",
            boxRaw(spawn, sMid, sLen, thin, full, dir, pipeType.stripeMaterial, "stripe")
        )
    }

    /**
     * 某个连接面上的**抽取黑板**（只在 [FaceMode.EXTRACT] 时画）。
     *
     * 这是给玩家的**视觉反馈**：面模式是配置，光看管子本身看不出来。
     * 抽取出液 / 取电的那一端贴一块大黑板，一眼就知道"这里在往外抽"。
     *
     * 画法见 [spawnExtractPlate]（复刻 Pipez 的 `SHAPE_EXTRACT_*`）。
     */
    private fun spawnExtractPlateIfExtract(spawn: Location, face: BlockFace) {
        if (faceMode(face) != FaceMode.EXTRACT) return
        spawnExtractPlate(spawn, face, FACE_REACH - EXTRACT_PLATE_THICK / 2.0)
    }

    /**
     * 按**管轴方向**把横截面 [sx]×[sy] 铺到世界坐标轴上。
     *
     * 这是 `box` 的"已知中点 + 长度"版本，供 [spawnRod] 让管身和彩条共用同一组端点。
     */
    private fun boxRaw(
        spawn: Location,
        mid: Vector,
        len: Double,
        sx: Float,
        sy: Float,
        dir: Vector,
        material: Material,
        variant: String
    ): ItemDisplay {
        val (lx, ly, lz) = when {
            abs(dir.x) > 0.5 -> Triple(len, sx.toDouble(), sy.toDouble())
            abs(dir.y) > 0.5 -> Triple(sx.toDouble(), len, sy.toDouble())
            else -> Triple(sx.toDouble(), sy.toDouble(), len)
        }

        return ItemDisplayBuilder()
            .transformation(
                TransformBuilder()
                    .translate(mid.x, mid.y, mid.z)
                    .scale(lx, ly, lz)
                    .build()
            )
            .itemStack(lineItem(material, variant))
            .build(spawn)
    }

    /**
     * 造一个盒子：[from]→[to] 是轴线（相对 [spawn] 的偏移），[sx] / [sy] 是**横截面**尺寸。
     *
     * ## ⚠️ 这里刻意**不用 `lookAlong`**
     *
     * `LineBuilder` / `TransformBuilder.lookAlong` 在**方向平行于 Y 轴时会退化**
     * （"看向正上方"是 look-at 变换的经典奇异点）。竖直的管子会被转歪，
     * 看起来像"被折断的"、或者凭空多出一截短壁。
     *
     * 而我们的管子**永远只有六个正交方向**，所以根本不需要旋转 ——
     * 直接在**世界坐标轴**上指定三个方向的尺寸就行：
     *
     * | 管轴 | 三个方向的尺寸 |
     * |---|---|
     * | X | (长度, sx, sy) —— 截面落在 Y / Z |
     * | Y | (sx, 长度, sy) —— 截面落在 X / Z |
     * | Z | (sx, sy, 长度) —— 截面落在 X / Y |
     *
     * 顺带一个好处：彩条的朝向在所有方向上都一致，接头处能对上。
     */
    private fun box(
        spawn: Location,
        from: Vector,
        to: Vector,
        sx: Float,
        sy: Float,
        material: Material,
        variant: String
    ): ItemDisplay {
        val mid = from.clone().add(to).multiply(0.5)
        val length = from.distance(to)
        val dir = to.clone().subtract(from).normalize()

        val (lx, ly, lz) = when {
            abs(dir.x) > 0.5 -> Triple(length, sx.toDouble(), sy.toDouble())
            abs(dir.y) > 0.5 -> Triple(sx.toDouble(), length, sy.toDouble())
            else -> Triple(sx.toDouble(), sy.toDouble(), length)
        }

        return ItemDisplayBuilder()
            .transformation(
                TransformBuilder()
                    .translate(mid.x, mid.y, mid.z)
                    .scale(lx, ly, lz)
                    .build()
            )
            .itemStack(lineItem(material, variant))
            .build(spawn)
    }

    /** 画线用的物品。自定义模型数据字符串留给资源包按变体换贴图。 */
    private fun lineItem(material: Material, variant: String) =
        ItemStackBuilder.of(material).addCustomModelDataString("${key}:$variant")

    // ---------------------------------------------------------------- 扳手 / 升级

    /** 这个面的模式。没配过就是默认的 [FaceMode.DEFAULT]（普通）。 */
    fun faceMode(face: BlockFace): FaceMode = faceModes[face] ?: FaceMode.DEFAULT

    /** 这个面被设成"断路"（等价于旧的"被扳手禁用"）。 */
    fun isDisabled(face: BlockFace): Boolean = faceMode(face) == FaceMode.NONE

    /** 这个面插着的升级模块；没插返回 null。 */
    fun sideUpgrade(face: BlockFace): ItemStack? = sideUpgrades[face]

    /**
     * 这个面的档位 —— **本面槽里的模块**（Pipez 的 `getUpgrade(side)`）。
     *
     * 没插模块 → [legacyTier]（老存档的"整管档位"，否则就是基础档）。
     *
     * ## ⚠️ 这个档位**只在"抽取面"上才有电学意义**
     *
     * 对标 Pipez 的 `pullEnergy`：速率是 `getRate(tileEntity, side)`，其中 `side` 是
     * **正在抽取的那一面**。接收面根本不参与那次搬运。
     *
     * 所以 [refreshElectric] 里**只给"抽取面"那条边写 `powerLimit`**，
     * 其余边一律不限流。见那里的注释。
     */
    fun tierOf(face: BlockFace): ConduitTier =
        upgradeTierOrNull(face) ?: legacyTier

    /**
     * 往某个面的槽里放 / 拿升级模块（[stack] 为 null = 拔出）。
     *
     * 这就是 Pipez `setUpgradeItem(side, upgrade)` 的等价物 ——
     * **物品留在槽里，不是被吃掉**。返回被换下来的旧模块（没有则 null），
     * 调用方负责还给玩家。
     */
    fun setSideUpgrade(face: BlockFace, stack: ItemStack?): ItemStack? {
        if (face !in CARDINAL_FACES) return null
        val old = sideUpgrades.remove(face)
        // ⚠️ 存**副本**：调用方可能把 ItemStack 交给虚拟库存继续用，
        // 共享同一个引用会让"界面里的物品"和"管子里的物品"互相串改。
        if (stack != null && stack.type != Material.AIR) sideUpgrades[face] = stack.clone()
        // 档位变了 → 这一面的边限流要重写
        applyTierToFace(face)
        ConduitNetwork.markAround(block.position)
        return old
    }

    fun redstoneMode(face: BlockFace): RedstoneMode = sideRedstone[face] ?: RedstoneMode.DEFAULT

    fun distribution(face: BlockFace): Distribution = sideDistribution[face] ?: Distribution.DEFAULT

    fun filterMode(face: BlockFace): FilterMode = sideFilterMode[face] ?: FilterMode.DEFAULT

    fun setRedstoneMode(face: BlockFace, mode: RedstoneMode) {
        if (face !in CARDINAL_FACES) return
        if (mode == RedstoneMode.DEFAULT) sideRedstone.remove(face) else sideRedstone[face] = mode
        ConduitNetwork.markAround(block.position)
    }

    fun setDistribution(face: BlockFace, mode: Distribution) {
        if (face !in CARDINAL_FACES) return
        if (mode == Distribution.DEFAULT) sideDistribution.remove(face) else sideDistribution[face] = mode
        ConduitNetwork.markAround(block.position)
    }

    fun setFilterMode(face: BlockFace, mode: FilterMode) {
        if (face !in CARDINAL_FACES) return
        if (mode == FilterMode.DEFAULT) sideFilterMode.remove(face) else sideFilterMode[face] = mode
        ConduitNetwork.markAround(block.position)
    }

    // ---------------------------------------------------------------- 过滤器

    /** 这个面的过滤器列表（只读视图）。 */
    fun filters(face: BlockFace): List<ItemStack> = sideFilters[face].orEmpty()

    /**
     * 这个面的过滤器**在管道类型层面**是否可用。
     *
     * - **能量管**：没有过滤器 —— 电没有"种类"可过滤（Pipez 的 `EnergyPipeType.hasFilter()`
     *   同样返回 `false`）。
     * - **流体管 / 物品管**：有过滤器。
     *
     * ⚠️ 这只是"管道类型支不支持"；**还要过 [canChangeFilter] 的档位关**（要不要装升级模块）。
     */
    fun hasFilter(face: BlockFace): Boolean = pipeType.category != PipeType.Category.ENERGY

    /**
     * **管道类型**层面支不支持改**分配模式**。
     *
     * 能量 / 流体的路由分别由 Rebar 的 `ElectricNetwork` / `FluidManager` 自己算，
     * 我们插不进去 → 改不了。只有**物品管**（传输是我们自己写的）才改得动。
     */
    fun pipeSupportsDistribution(): Boolean = pipeType.category == PipeType.Category.ITEM

    /**
     * **管道类型**层面支不支持**过滤模式 / 过滤器列表**。
     *
     * 能量管没有过滤器；流体 / 物品的过滤效果要等自研传输接上。
     */
    fun pipeSupportsFilter(): Boolean = pipeType.category != PipeType.Category.ENERGY

    // ---------------------------------------------------------------- 档位解锁

    /**
     * 这一面**插着的升级模块对应的档位**；没插模块返回 null。
     *
     * 对标 Pipez 的 `getUpgrade(side)` —— **null 就代表"什么都配不了"**
     * （`Upgrade.canChange*(null)` 全是 false）。所以界面上的
     * 红石 / 分配 / 过滤 三个按钮在没模块时**全部锁定**。
     *
     * ⚠️ 老存档兼容：旧版本档位是"整管一个"（没有模块物品）。那种情况下
     * [legacyTier] 非 BASIC 就当作"装了那一档的模块"，行为不变。
     */
    fun upgradeTierOrNull(face: BlockFace): ConduitTier? {
        val stack = sideUpgrades[face]
        if (stack != null) {
            val rebar = RebarItem.fromStack(stack, UpgradeItem::class.java) ?: return null
            return ConduitTier.fromItemKey(rebar.key.key)
        }
        return if (legacyTier != ConduitTier.BASIC) legacyTier else null
    }

    /** 这一面能不能改**红石模式** —— 装没装模块 + 模块档位够不够。 */
    fun canChangeRedstone(face: BlockFace): Boolean =
        upgradeTierOrNull(face)?.canChangeRedstone == true

    /** 这一面能不能改**分配模式** —— 管道类型支持 **且** 档位够。 */
    fun canChangeDistribution(face: BlockFace): Boolean =
        pipeSupportsDistribution() && upgradeTierOrNull(face)?.canChangeDistribution == true

    /** 这一面能不能改**过滤模式 / 过滤器列表** —— 管道类型支持 **且** 档位够。 */
    fun canChangeFilter(face: BlockFace): Boolean =
        pipeSupportsFilter() && upgradeTierOrNull(face)?.canChangeFilter == true

    /**
     * 本管段**有没有升级槽** —— 只有能量管有（对齐 Pipez 的 `hasUpgrade()`）。
     *
     * 流体管的传输速率由 `config.yml` 的 `fluid.per-second` 统一给，
     * 没有"档位"可言，所以不给升级槽，免得玩家插了模块却看不到任何效果。
     */
    fun acceptsUpgrade(): Boolean = pipeType.category == PipeType.Category.ENERGY

    /** 往某个面加一条过滤（返回是否加成功 —— 重复的不加）。 */
    fun addFilter(face: BlockFace, stack: ItemStack): Boolean {
        if (face !in CARDINAL_FACES || stack.type == Material.AIR) return false
        val list = sideFilters.getOrPut(face) { ArrayList() }
        if (list.any { it.isSimilar(stack) }) return false
        list += stack.clone().apply { amount = 1 }
        ConduitNetwork.markAround(block.position)
        return true
    }

    /** 删掉某个面的第 [index] 条过滤，返回被删掉的物品（越界返回 null）。 */
    fun removeFilter(face: BlockFace, index: Int): ItemStack? {
        val list = sideFilters[face] ?: return null
        if (index !in list.indices) return null
        val removed = list.removeAt(index)
        if (list.isEmpty()) sideFilters.remove(face)
        ConduitNetwork.markAround(block.position)
        return removed
    }

    /** 清空某个面的过滤器。 */
    fun clearFilters(face: BlockFace) {
        if (sideFilters.remove(face) != null) ConduitNetwork.markAround(block.position)
    }

    /**
     * 红石条件是否满足 —— 复刻 Pipez 的 `PipeLogicTileEntity.shouldWork`。
     *
     * ⚠️ 这个**真的生效**：[connectsTo] 会拿它把关，不满足的面直接不连。
     */
    fun redstoneAllows(face: BlockFace): Boolean = when (redstoneMode(face)) {
        RedstoneMode.IGNORED -> true
        RedstoneMode.OFF_WHEN_POWERED -> !isRedstonePowered()
        RedstoneMode.ON_WHEN_POWERED -> isRedstonePowered()
        RedstoneMode.ALWAYS_OFF -> false
    }

    private fun isRedstonePowered(): Boolean =
        block.isBlockPowered || block.isBlockIndirectlyPowered

    /**
     * 设置某个面的模式。返回是否真的变了（变了才重算网络）。
     *
     * [ConduitNetwork.markAround] 会连带重刷渲染（形状掩码变了）和连线，
     * 所以这里不用另调 [refresh]。
     */
    fun setFaceMode(face: BlockFace, mode: FaceMode): Boolean {
        if (face !in CARDINAL_FACES) return false
        val current = faceMode(face)
        if (current == mode) return false

        // 全默认时**不存**这个键（见 [faceModesAreDefault]），所以写回时也要处理：
        // 改回默认值就把这条从 map 里删掉，否则留着。
        if (mode == FaceMode.DEFAULT) faceModes.remove(face) else faceModes[face] = mode

        // ⚠️ 复刻 Pipez：`UpgradeTileEntity.setExtracting(side, false)` 里
        // **一旦某个面不再抽取，那个面的升级模块就被丢出来**
        // （`Containers.dropContents`）。不丢的话模块会"挂"在一个不再抽取的面上，
        // 玩家既看不到也用不上。
        if (current == FaceMode.EXTRACT && mode != FaceMode.EXTRACT) {
            sideUpgrades.remove(face)?.let { leftover ->
                block.world.dropItem(block.location.toCenterLocation(), leftover)
            }
        }

        ConduitNetwork.markAround(block.position)
        return true
    }

    /**
     * 扳手（**潜行 + 右键**）在某个面上的循环 —— 复刻 Pipez 的 `onWrenchClicked`。
     *
     * ## 两种循环，取决于"这一面是不是末端"
     *
     * Pipez 的判据是 `world.getBlockState(pos.relative(side)).getBlock() != this`
     * —— 也就是"那一格是不是**同一根管子**"。这里同理，但额外要求**同类型**：
     * 能量管和流体管并排时，对能量管来说那一格不是"同一根管子"（永远连不上），
     * 所以按**末端**处理。
     *
     * - **末端**（对面不是同类型管：空气 / 机器 / 异色管）
     *   → 在 `普通 → 抽取 → 断路` 之间循环（见 [FaceMode.TERMINAL_CYCLE]）。
     * - **两管之间**（对面是**同类型**管）
     *   → 只在 `断 / 连` 之间切换 —— 管子之间没有"抽取"可言
     *     （Pipez 里点两管之间也是直接断路）。
     *
     * @return 切换后的模式，方便调用方播提示音 / 发消息。
     */
    fun wrenchCycle(face: BlockFace): FaceMode {
        val neighbor = rebarAt(block.getRelative(face))
        val sameTypePipe = neighbor is ConduitBlock && neighbor.pipeType == pipeType

        val current = faceMode(face)
        val next = if (sameTypePipe) {
            if (current == FaceMode.NONE) FaceMode.NORMAL else FaceMode.NONE
        } else {
            val cycle = FaceMode.TERMINAL_CYCLE
            val index = cycle.indexOf(current)
            cycle[(index + 1) % cycle.size]
        }

        setFaceMode(face, next)
        return next
    }

    /**
     * 把这一面的档位重写到它那条边（只有能量管才有"边限流"）。
     *
     * ⚠️ **只有"抽取面"才写上限**，其余面写回"不限" —— 对标 Pipez 的
     * `pullEnergy`：速率属于抽取面，接收面不参与那次搬运。详见 [applyEdgeLimit]。
     *
     * 只在"这一面接着机器端口、而且已经连上"时才需要 —— 管段之间的边不设限流。
     */
    private fun applyTierToFace(face: BlockFace) {
        if (pipeType.category != PipeType.Category.ENERGY) return
        val node = nodeOrNull() ?: return
        val neighbor = rebarAt(block.getRelative(face)) as? ElectricRebarBlock ?: return
        val port = ConduitLinker.portNodeFacing(neighbor, face.oppositeFace) ?: return
        if (!node.isConnectedTo(port)) return
        applyEdgeLimit(node, port, if (faceMode(face).directional) tierOf(face) else null)
    }

    // ---------------------------------------------------------------- 网络

    fun nodeOrNull(): ElectricConnectorNode? =
        getElectricNode(NODE_NAME, ElectricConnectorNode::class.java)

    /**
     * 节点只建一次。
     *
     * 为什么幂等：节点会随方块 PDC 持久化，而且**没有 API 能删除节点**。
     * 如果每次 `postLoad` 都新建一个，存档里会越积越多。
     */
    private fun ensureNode(): ElectricConnectorNode =
        nodeOrNull() ?: addElectricNode(ElectricConnectorNode(NODE_NAME, block.position))

    /**
     * 幂等地把本管段的节点连到"应该连的"目标上。
     *
     * 关键：**先算目标集合，再和现有连接求差集**，只断多余的、只连缺的。
     * 千万不要"先 disconnectAll 再全连"—— 每一次 connect/disconnect 都会触发
     * Rebar 的网络合并（O(网络规模)），那样批处理就白做了。
     */
    fun refresh(forceDisplays: Boolean = false) {
        when (pipeType.category) {
            PipeType.Category.ENERGY -> refreshElectric()
            PipeType.Category.FLUID -> refreshFluid()
            // 物品管还没做，没有网络要刷
            PipeType.Category.ITEM -> Unit
        }

        // 形状变了就重建显示实体（真实实体，spawn/remove 都不便宜，所以要去重）
        if (forceDisplays) {
            // ⚠️ 强制重建：给"run 起点"用。
            //
            // 为什么起点必须强制：贪婪合并把一整条直线交给**起点格**画，所以
            // **起点格的渲染取决于整条 run 的长度**，而它自己的形状掩码在
            // "远端加了一格"时**根本没变**。用掩码去重 → 起点跳过重建 →
            // 新加的那一格永远不显示（用户："放了能量管道但没渲染"）。
            // 见 [ConduitNetwork.flush] 里"把 run 起点拉进 batch"的那一步。
            lastDisplayMask = shapeMask()
            rebuildDisplays()
        } else {
            refreshDisplaysIfShapeChanged(shapeMask())
        }
    }

    /**
     * 能量管：把节点连到"应该连的"目标上。
     *
     * 关键：**先算目标集合，再和现有连接求差集**，只断多余的、只连缺的。
     * 千万不要"先 disconnectAll 再全连"—— 每一次 connect/disconnect 都会触发
     * Rebar 的网络合并（O(网络规模)），那样批处理就白做了。
     *
     * ## ⚠️ "连着连着就断"的真正修法在这里
     *
     * 之前只要 `ConduitLinker.portNodeFacing` 返回 null 就认为"该断"，于是：
     * 机器端口实体**还没加载**（区块加载时序 / 同 tick 内先刷管道后建端口）时，
     * 一条本来合法的边会被断掉；而端口加载完**没有任何事件**去重刷这根管道 ——
     * 于是就永久断了。用户描述的就是这个："连着连着就断，尤其连着机器"。
     *
     * 现在三档分明（见 [machineLinkState]）：
     * - [LinkState.PRESENT] → 进 targets（该连就连）
     * - [LinkState.PENDING] → **不动**（既不连也不断，保持现状；）
     * - [LinkState.ABSENT]  → 不进 targets（该断就断）
     *
     * 再加 [registerPortLoadWatch] 在端口加载时主动重刷，PENDING 不会卡死。
     */
    private fun refreshElectric() {
        val node = ensureNode()

        val targets = LinkedHashSet<ElectricNode>()
        val pending = HashSet<ElectricNode>()
        // 每个目标对应的"那一面的档位" —— 边限流**按面**算
        // （Pipez 的速率本来就是每面一个升级模块决定的）。
        val targetTiers = HashMap<ElectricNode, ConduitTier>()
        // 被标成"抽取"的机器端口：那条边只允许 机器 → 管段。
        // 同类管之间的边永远是双向（管段之间没有"方向"，方向只在"管 ↔ 机器"这一层）。
        val extractPorts = ArrayList<ElectricNode>()

        for (face in CARDINAL_FACES) {
            if (isDisabled(face)) continue
            val neighbor = rebarAt(block.getRelative(face)) ?: continue
            // ⚠️ 必须和**渲染**用同一个判据（`connectsTo`），其中包括
            // [MachinePortGuard] 的防自短接。不然被挡掉的那一面虽然不画胳膊，
            // 电却照样连过去 —— 防短接就白做了。
            // （PENDING 的端口 `connectsTo` 返回 true，所以不会被误跳过。）
            if (!connectsTo(face)) continue
            val state = machineLinkState(neighbor, face)
            val target: ElectricNode? = when {
                neighbor is ConduitBlock ->
                    if (neighbor.pipeType == pipeType) neighbor.nodeOrNull() else null

                neighbor is ElectricRebarBlock ->
                    ConduitLinker.portNodeFacing(neighbor, face.oppositeFace)

                else -> null
            }

            if (target != null && target !== node) {
                targets += target
                targetTiers[target] = tierOf(face)
                // 只有"接机器"的边才受面模式影响（邻段是同类管时上面 target 也是它自己的节点，
                // 这里用 faceMode 判一下：只有 EXTRACT 才有方向，管间无方向）。
                if (neighbor is ElectricRebarBlock && neighbor !is ConduitBlock && faceMode(face).directional) {
                    extractPorts += target
                }
            } else if (neighbor is ConduitBlock && state != LinkState.ABSENT) {
                // 邻段是同类管、但它的节点**还没建出来**（区块刚加载 / postLoad 顺序）
                // → 别急着断，等它建好后自己会 markAround 把边补回来。
                lastConnectedConduitNode(neighbor)?.let { pending += it }
            } else if (state == LinkState.PENDING && neighbor is ElectricRebarBlock) {
                // 端口还没加载：把"上次连着的那个节点"找回来，放进 pending 保护名单，
                // 下面求差集时就不会把它断掉。
                lastConnectedPort(neighbor)?.let { pending += it }
                // 登记"端口加载后重刷"，保证 PENDING 一定会被解决
                registerPortLoadWatch(neighbor, face)
            }
        }

        // 断掉已经不该连的（邻居被拆 / 被扳手禁用 / 机器那一面确定没端口）。
        // ⚠️ pending 里的**不断** —— 端口还在加载，断了就再也连不回来。
        for (uuid in node.connections.toList()) {
            val existing = ElectricityManager.getNodeById(uuid) ?: continue
            if (existing in targets) continue
            if (existing in pending) continue
            node.disconnectFrom(existing)
        }

        // 连上还缺的
        for (target in targets) {
            if (!node.isConnectedTo(target)) node.connect(target)
        }

        // 边限流：**每次 refresh 都重写**（不只是新连的边）。
        // 玩家可能只是把某个面的升级模块换了一档、连接没变，
        // 这时差集逻辑不会碰这条边，但限流值必须跟着更新。
        //
        // ⚠️ **只有"抽取面"那条边限流**，其余边写回"不限"。
        // 对标 Pipez 的 `pullEnergy`：`getRate(tileEntity, side)` 里的 `side` 是
        // **正在抽取的那一面**，接收面不参与那次搬运。
        // 而 `calculateLoadOnEdges` 会让**路径吞吐 = 路径上所有边的最小值**，
        // 所以把上限写在接收面上（比如"管道→机器"那条）会把整条路径压死 ——
        // 实测教训：究极插在电源面、机器面没插 → 压到基础档 8192 W →
        // 下界合金只带得动 2 组并行（2×3200=6400 ✔ / 3×3200=9600 ✘）。
        for (target in targets) {
            applyEdgeLimit(node, target, if (target in extractPorts) targetTiers[target] else null)
        }

        // 逐面套上方向限制。**每次 refresh 都要重写**（包括已经连上的边）——
        // 玩家可能只改了模式、没改连接，这时上面的差集逻辑不会碰这条边，
        // 但方向必须更新，否则界面上切了"抽取"却还是双向传。
        applyDirections(extractPorts)
    }

    /**
     * 把 [extractPorts] 里这些"抽取端"的边设成单向（机器 → 管段），
     * 其余"管 ↔ 机器"的边恢复双向。
     *
     * ## ⚠️ `unidirectional` 的语义：`Edge(X, Y).unidirectional = true` = 「**只准 X → Y**」
     *
     * 反编译 `ElectricNetwork.findBestPath`（`javap -c -p -l`）**确凿**：
     * ```
     * LocalVariableTable:  slot 9 = current,  slot 12 = neighbor
     *
     * 372: new Edge(aload 12, aload 9)   // 参数顺序 = Edge(neighbor, current)
     * 383: getUnidirectional()
     * 386: ifne 271                      // ← 为 true 就跳过，不走 current→neighbor
     * ```
     * 判的是 `Edge(neighbor, current)`，为 true 就禁止 `current → neighbor`。
     * 也就是说 **`Edge(X, Y)` 上标 true，禁止的是 Y→X**，只留下 X→Y。
     *
     * ## 踩过的两个坑（都别再来）
     *
     * 1. **两边都标 true**（0.1.42 及之前）→ 不是"单向"，是**双向封死**：
     *    设成"抽取"以后那条边彻底不通，机器能量一点都不减。
     * 2. **两边都标 false**（0.1.43 我"修"反了）→ 完全放行，`抽取` 退化成普通模式，没任何效果。
     *
     * ## 正确做法
     *
     * "只允许 机器 → 管段"（抽取，管子从机器里取电）：
     * - `Edge(port, node).unidirectional = true`  → **只准** 机器 → 管段
     * - `Edge(node, port).unidirectional = false` → 别把反方向也封了
     */
    private fun applyDirections(extractPorts: List<ElectricNode>) {
        val node = nodeOrNull() ?: return
        val touched = HashSet<ElectricNode>()

        for (port in extractPorts) {
            if (port === node) continue
            touched += port
            // 只有 EXTRACT 会进这里（见 [directionOf]）：电力只允许 **机器端口 → 管段**。
            runCatching {
                // 只准 机器 → 管段（禁止 管段 → 机器）
                ElectricNetwork.Edge(port, node).unidirectional = true
                // 反方向不能标 true，否则就把 机器 → 管段 也封死了
                ElectricNetwork.Edge(node, port).unidirectional = false
            }
        }

        // 清理：以前设过方向、现在不该有的边 → 恢复双向。
        // 判据：本节点所有连接里，既不在 [touched]、又曾经是单向的（unidirectional=true）
        // 且对端不是同类管 —— 直接清掉最省事（管间边本来就不该有方向）。
        for (uuid in node.connections) {
            val other = ElectricityManager.getNodeById(uuid) ?: continue
            if (other in touched) continue
            if (other is ElectricConnectorNode) continue // 同类管，双向
            runCatching {
                ElectricNetwork.Edge(node, other).unidirectional = false
                ElectricNetwork.Edge(other, node).unidirectional = false
            }
        }
    }

    /**
     * 上次连过的、位于 [machine] 上的端口节点。
     *
     * 用途：端口实体暂时读不到（PENDING）时，靠"现有连接里哪个节点属于这台机器"
     * 把它认回来，塞进 pending 保护名单，避免被差集逻辑误断。
     *
     * 机器每个面最多一个端口，所以"属于这台机器"就已经唯一确定了它是哪一面的口。
     */
    private fun lastConnectedPort(machine: ElectricRebarBlock): ElectricNode? {
        val node = nodeOrNull() ?: return null
        return node.connections.asSequence()
            .mapNotNull { ElectricityManager.getNodeById(it) }
            .firstOrNull { it in machine.electricNodes }
    }

    /**
     * 上次连过的、位于 [neighbor] 这根同类管上的节点。
     *
     * 用途和 [lastConnectedPort] 一样，只是对象换成"邻段自己的节点"：
     * 邻段的节点若还没建出来，这条边也要保护，不能断。
     *
     * 判据：现有连接里，属于 [neighbor] 那一个方块位置的节点。
     * 管段的节点位置就是方块位置（`ElectricConnectorNode(name, block.position)`），
     * 所以直接比位置即可。
     */
    private fun lastConnectedConduitNode(neighbor: ConduitBlock): ElectricNode? {
        val node = nodeOrNull() ?: return null
        return node.connections.asSequence()
            .mapNotNull { ElectricityManager.getNodeById(it) }
            .firstOrNull { it.block == neighbor.block.position }
    }

    /**
     * 让这台机器的端口实体一加载就重刷本管段。
     *
     * 为什么必须：端口实体可能在管道之后才加载。没有这个回调的话，
     * 上面那一步 PENDING 保护会一直"保护"着一个永远连不上的节点。
     *
     * ⚠️ **每个 (机器, 面) 只注册一次**。
     * `refresh()` 会被反复调用（每次邻接变化都调），如果每次都注册，
     * 回调表会越积越长，端口加载时会重复触发几十次重刷。
     * 用 [portWatchKeys] 记一下已经注册过的就够 —— 这台管段位置不变，
     * 邻居机器也不变，一次注册终身有效。
     */
    private fun registerPortLoadWatch(machine: ElectricRebarBlock, face: BlockFace) {
        val key = PortWatchKey(machine.block.position, face)
        if (!portWatchKeys.add(key)) return
        for (node in machine.electricNodes) {
            val name = ConduitLinker.portHeldEntityName(node.id)
            machine.whenHeldRebarEntityLoads(name) {
                ConduitNetwork.markAround(block.position)
            }
        }
    }

    /**
     * 流体管：把流体点连到"应该连的"点。
     *
     * 真正的逻辑在 [ConduitFluid]（那边只在端点 / 交汇处建点，长直管中间不建）。
     */
    private fun refreshFluid() {
        ConduitFluid.refresh(this)
    }

    /**
     * 给这条边设容量上限。[tier] 为 `null` = **不限流**（写 Rebar 的默认值 `MAX_VALUE`）。
     *
     * ## 什么时候才该传非 null —— 只有"抽取面"
     *
     * 对标 Pipez 的 `EnergyPipeType.pullEnergy`：
     * ```java
     * if (!tileEntity.isExtracting(side)) return;              // 只有抽取面动
     * int energyToTransfer = getRate(tileEntity, side);        // 速率 = 【抽取面】的升级
     * ```
     * **速率只属于抽取面**，接收面不参与那次搬运（`insert` 那边没有 rate 限制）。
     *
     * 所以 [refreshElectric] 只给"抽取面 ↔ 机器"那条边写上限，其余边一律不限。
     *
     * ## ⚠️ 为什么这条边**必须**限
     *
     * Rebar 的 `ElectricConsumerNode` **没有能量缓冲**：每 tick 必须给满 `requiredPower`，
     * 给不满就 `isPowered = false`、机器**完全不工作**（不是变慢）。
     * 而 `calculateLoadOnEdges` 用 `powerLimit − 已用` 卡每条边，
     * **路径吞吐 = 路径上所有边里最小的那个**。
     *
     * 所以"在哪条边上写上限"非常关键 —— 写错边（比如写在接收面）就会莫名其妙地把
     * 整条路径压死。实测教训：究极插在电源面、机器面没插 → 被压到基础档 8192 W →
     * 下界合金只带得动 2 组并行（2×3200=6400 ✔ / 3×3200=9600 ✘）。
     *
     * ## 存储细节
     *
     * `Edge.powerLimit` 的键是**有方向**的（`from.id → to.id`），所以两个方向都要写。
     * 每次都写（不限也写 `MAX_VALUE`）是刻意的：旧版本留在世界 PDC 里的旧上限会被
     * **覆盖清掉**，服主不用手动清理存档。
     *
     * 整体开关：`config.yml` 的 `conduit.tier-power-limit`（false = 永远不限）。
     */
    private fun applyEdgeLimit(a: ElectricNode, b: ElectricNode, tier: ConduitTier?) {
        val limit = if (PipezConfig.tierPowerLimit && tier != null) {
            PipezConfig.capacity(tier)
        } else {
            Double.MAX_VALUE
        }
        runCatching {
            ElectricNetwork.Edge(a, b).powerLimit = limit
            ElectricNetwork.Edge(b, a).powerLimit = limit
        }
    }

    companion object {
        /** 节点名。一旦发布不要改，否则旧存档取不到自己的节点。 */
        const val NODE_NAME = "conduit"

        /** 所有展示实体名都以它开头，[clearDisplays] 靠它区分自己建的实体。 */
        const val DISPLAY_PREFIX = "vis:"

        fun bodyName(face: BlockFace): String = "${DISPLAY_PREFIX}body:${face.name}"

        fun bandName(face: BlockFace): String = "${DISPLAY_PREFIX}band:${face.name}"

        /** 相邻两段在方块中心的接缝重叠量，防止出现缝。 */
        const val OVERLAP = 0.02

        /** 方块中心到表面的距离。 */
        const val FACE_REACH = 0.5

        /** 尽头（管口）比方块表面再多伸出来多少 —— "端口这里应该出来一点点"。 */
        const val END_PROTRUSION = 0.04

        /** 沿直线找尽头时的最大步数，防止异常数据把 tick 拖死。 */
        const val MAX_RUN = 512

        /** 每个方向之间错开的尺寸（防 Z-fighting）。0.0008 足够，肉眼看不出来。 */
        const val JITTER_STEP = 0.0008f

        /**
         * 彩条沿管轴方向比管身**两端各多出**多少。
         *
         * 让彩条端面离开管身端面，消除管口那一圈 Z-fighting。
         * 0.006 大约 1/50 格，肉眼几乎看不出，但足以让两片端面分开。
         */
        const val STRIPE_END_POKE = 0.006

        /**
         * 抽取黑板（Pipez 的 `SHAPE_EXTRACT_*`）的横截面**半宽**。
         *
         * Pipez 原值：`Block.box(4, 15, 4, 12, 16, 12)` → 8/16 = 0.5 宽，半宽 0.25。
         */
        const val EXTRACT_PLATE_HALF = 0.25

        /** 抽取黑板的**厚度**。Pipez 原值：16/16 - 15/16 = 1/16 = 0.0625。 */
        const val EXTRACT_PLATE_THICK = 0.0625

        /**
         * 抽取黑板的材质。
         *
         * 用户明确要求"抽取就是末端有个**大的黑色板**"，所以用黑色。
         * 和管身（黑混凝土）同色系，但板比管身宽得多，轮廓一眼能看出来。
         */
        val EXTRACT_PLATE_MATERIAL: Material = Material.BLACK_CONCRETE
    }
}

/** `ConduitFace` → Bukkit 方向。 */
fun ConduitFace.toBlockFace(): BlockFace = when (this) {
    ConduitFace.UP -> BlockFace.UP
    ConduitFace.DOWN -> BlockFace.DOWN
    ConduitFace.NORTH -> BlockFace.NORTH
    ConduitFace.SOUTH -> BlockFace.SOUTH
    ConduitFace.EAST -> BlockFace.EAST
    ConduitFace.WEST -> BlockFace.WEST
}

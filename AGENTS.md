# AGENTS.md

Lapis-Pipez —— 方块式多介质管道附属（Kotlin / Paper 26.2 / Java 25）。
**这个文件是给 AI 助手和未来的自己看的工作手册：先读这里，再去翻源码。**
设计推导看 [docs/conduit-10k-design.md](./docs/conduit-10k-design.md)。

## 这是什么

把"点对点拉线"升级成"方块式管道网"：管段贴上去自动连成任意拓扑，不用接线盒就能分叉。
目标规模是**稳态一万管段**，所以整套代码围绕"把每个管段的固定成本压到零"来写。

本项目约定：

- 内容命名空间 `pipez`（`PipezKeys.NAMESPACE` 与 `PipezAddon.getKey()` 必须一致）
- 包名 `top.mc506lw.rebar.pipez`
- 目录约定：`conduit/` 放管段与网络层，`items/` 放物品，`listeners/` 放事件接入，
  顶层放 `PipezAddon` / `PipezKeys` / `PipezItems` / `PipezBlocks` / `PipezPages` / `PipezConfig`
- 入口 `PipezAddon.onEnable` 里按顺序初始化 `PipezItems` → `PipezBlocks` → `PipezPages`，
  再注册 `ConduitLinkListener`，最后起 flush 定时任务
- **内容必须注册指南页**（`PipezPages`）：`SimpleStaticGuidePage` + `addItem` +
  `RebarGuide.rootPage.addPage`；新加内容记得加进 `pageItems`

## 三条铁律（改代码前先读）

1. **一个管段只能有一个电力节点，绝不能改成六个端口。**
   `createSimpleElectricPort` × 6 会让一万管段变成六万节点 + 六万展示实体。
   方向靠"和哪个邻居连"表达，不靠端口。

2. **形状不许持久化。**
   `ConduitShape` 从六个邻居推导形状；`ConduitBlock.write()` 只在"扳手禁用面"非零时写 1 个 byte。
   一旦你把形状写进 PDC，区块加载就要解析一万份数据，万级目标直接崩。

3. **`ConduitBlock.refresh()` 必须幂等。**
   先算目标集合，再和现有连接**求差集**，只断多余的、只连缺的。
   千万不要 `disconnectAll()` 再全连 —— 每次 connect/disconnect 都会触发 Rebar 的
   网络合并（O(网络规模)），那样批处理就白做了。

## ⚠️ 哪些 Rebar API 是 internal（本项目最重要的经验）

**字节码是 public 不等于 Kotlin 能用。** Rebar 大量 API 在 Kotlin 层标了 `internal`，
`javap` 看是 `public`，Kotlin 编译器直接拒绝：`Cannot access 'X': it is internal in file.`

**已确认取不到的（Kotlin `internal`）**：

| 类型 | 影响 | 本项目的对策 |
|---|---|---|
| `fluid.FluidManager` | 公开 API 无法组建流体网络（建得了点，连不了点） | `bridge/FluidBridge.kt` 反射 |
| `fluid.placement.FluidPipePlacementService` | 同上，且流体管道没有可视化 | 无对策（接受无渲染） |
| `logistics.LogisticGroup` / `logistics.slot.LogisticSlot` | 取不到物流槽 | 不用，改走原版 `InventoryHolder` |
| `block.interfaces.LogisticRebarBlock.logisticGroups` | 同上 | 同上 |
| `block.interfaces.VirtualInventoryRebarBlock.getVirtualInventories` | 取不到 Rebar 机器的虚拟库存 | `bridge/VirtualInventoryBridge.kt` 反射 |

**可以正常用的（public）**：`ElectricRebarBlock` / `SimpleElectricRebarBlock`、`ElectricNode`、
`ElectricConnectorNode`、`ElectricNetwork.Edge`、`TickingRebarBlock`、`BlockStorage`、
`EntityStorage`、`ElectricityManager`、`VirtualFluidPoint`、`FluidPointType`、`FluidRebarBlock`
（`createFluidPoint` 能建点，但连不起来）、原版 `InventoryHolder`、**InvUI 的 `VirtualInventory`**
（注意 `setItem` 的参数顺序是 `(UpdateReason, slot, stack)`，不是 `(slot, stack)`）。

**判断方法（照这个顺序来）**：

1. 先按 GitHub 源码的声明猜 —— **不可靠**，源码 HEAD 和 release 可能不一致
   （例：`LogisticSlot` 在 HEAD 是 public，在 0.44.4 release 取不到）。
2. 直接写代码编译一次 —— **唯一可靠**。报 `it is internal in file` 就是取不到。
3. 想确认字节码层面的方法名有没有被混淆，用 `javap -p -classpath <jar> <类>`：
   Kotlin 的 `internal` **成员**会被改名成 `foo$模块名`，但 `internal` **类**的方法通常保持原名
   （`FluidManager.add` / `connect` 都是原名，所以反射能直接调）。

**结论**：能量是纯公开 API；物品走公开 API + 反射取 Rebar 机器库存；流体必须全程反射。

## 反射的使用规矩

`bridge/` 是**唯一**允许出现反射的地方（目前 `FluidBridge` + `VirtualInventoryBridge`）。
新增反射调用必须满足：

- 集中在 `bridge/` 里，不要散落到业务代码
- 启动时解析一次、缓存 `Method`，**不在 tick 热路径里做查找**
- 任何一步失败只关掉对应功能，**绝不抛异常**影响其它功能
- 有 config 开关（`bridge.use-reflection`）可以整体关闭
- 在启动日志里明确标注这是 unsupported API
- 反射只用来**拿到对象**；拿到之后的读写尽量走公开 API
  （例：`VirtualInventory` 本身是 InvUI 公开类，拿到后就不用再反射了）

## 批处理模型

所有邻接变化（放置 / 破坏 / 扳手 / 区块加载）**只调 `ConduitNetwork.markAround(pos)`**，
把位置塞进 dirty 队列；真正的重算在 `PipezAddon` 起的定时任务里 `ConduitNetwork.flush()` 统一做。

**事件回调里不要直接算连接。** 一个 tick 内改多少管段都只 flush 一次，
这样重算次数与"受影响的连通块"有关，而不是与"改动数"成正比。

## 构建 / 验证

| 目的 | 命令 |
|---|---|
| 编译 + 单测 + 打包 | `./gradlew build` → `build/libs/Lapis-Pipez-0.1.3.jar` |
| 只编译（快） | `./gradlew compileKotlin` |
| 只跑测试 | `./gradlew test` |

- `gradle.properties` 里写死了本机 `org.gradle.java.home=D:\Java\zulu25`。换机器要么改这里，要么删掉让它用 `JAVA_HOME`。
  CI（`.github/workflows/build.yml`）会自动删掉这一行，所以别依赖它。
- 源码、资源、语言文件统一 UTF-8（`gradle.properties` 已强制），**别改编码相关配置**。

## 怎么查 Rebar / Pylon 源码（重点）

Rebar 是 `compileOnly` 依赖，**没有本地源码**，遇到不确定的 API 必须自己查。按效率排序：

**1. 直接查 GitHub（最快，优先用这个）**

```bash
# 搜某个类/方法在哪个文件
gh search code "ElectricConnectorNode" --repo pylonmc/rebar
# 列出整个仓库的文件树，再 grep 关键字
gh api "repos/pylonmc/rebar/git/trees/HEAD?recursive=1" --jq '.tree[].path' | grep -i conduit
# 直接读某个文件
gh api "repos/pylonmc/rebar/contents/<路径>" --jq '.content' | base64 -d
```

- 框架本体：`pylonmc/rebar`
- 官方内容实现（**最佳写法参考**）：`pylonmc/pylon`

**2. 本地 gradle 缓存里的 jar（看真实签名和可见性）**

```
~/.gradle/caches/modules-2/files-2.1/io.github.pylonmc/rebar/<版本>/<hash>/rebar-<版本>.jar
```

```bash
D:/Java/zulu25/bin/javap.exe -p -classpath <jar> <类>      # 看 API 签名
D:/Java/zulu25/bin/javap.exe -p -classpath <jar> 'a.b.C$D' # 看嵌套类（注意转义 $）
```

**⚠️ 判断"能不能做"时，`public` 与 `internal` 的差别是决定性的。**
例如 `ElectricConnectorNode(String, BlockPosition)` 是 public 构造器 → 能造裸节点；
`ElectricNetwork.Edge(from, to)` 是 public 构造器 → 能自己写边限流。
凡是 `private` / `@ApiStatus.Internal` 的，都当它不存在。

**3. Maven Central 直接下 jar**（缓存里没有时）

```
https://repo1.maven.org/maven2/io/github/pylonmc/rebar/<版本>/rebar-<版本>.jar
```

## 这个生态里踩过的坑

- **`RebarUtils` 是 Kotlin 文件门面**（`@file:JvmName`）：Kotlin 里不能写 `RebarUtils.XXX`。
- **节点删不掉。** Rebar 把 `ElectricNode` 写进方块 PDC，**没有公开 API 能删节点**。
  所以 `ConduitBlock.ensureNode()` 必须幂等：`getElectricNode(name)` 拿不到才 `addElectricNode`。
  动态变化的只有"边"（`connect` / `disconnectFrom`）。
- **破坏时 Rebar 会替你清理连接。** `ElectricRebarBlock` 的伴生对象监听 `RebarBlockBreakEvent`，
  对每个节点执行 `disconnectAll()` + `ElectricityManager.removeNode()`。
  所以**不会留悬空连接**，不需要像 Lapis-Cable 那样定期扫脏数据。
- **但节点会占 PDC。** `ElectricRebarBlock` 的 `onSerialize` 会把节点（含 connections）写进 PDC，
  约 100 字节/管段。想省掉只能不用 `ElectricRebarBlock`，自己管节点生命周期。
- **`ElectricNetwork.Edge.powerLimit` 的存储键是有方向的**（`from.id → to.id`），
  想让一条边双向都限流，两个方向都要写。
- **`InteractRebarItemHandler.onInteract` 会被投递多次。** 用
  `@MultiHandler(priorities = [EventPriority.HIGH])` 保证只收到一次 ——
  否则扳手会把同一个面来回切好几遍。
- **机器的端口节点没有"面"信息。** `ElectricPortEntity` 不暴露 face，只能拿端口展示实体的
  世界坐标减去方块中心反推（`ConduitLinker`）。这是 best-effort：端口实体没加载时会延迟到下次 flush。
- **展示实体的坐标是相对方块中心的**（方块表面在 ±0.5）：贴面实体必须凸出 0.5 一点点，
  否则会被不透明方块本体挡住。
- **`BlockStorage.get(...)` 在区块未加载时会抛 `IllegalArgumentException`**
  （`You can only get Rebar blocks in loaded chunks`）。
  **一律用 `rebarAt(...)`**（本项目 `conduit/RebarAccess.kt`），它先查 `isChunkLoaded`。
  坑点在于：批处理 `flush()` 消费的是**上一 tick 登记**的位置，
  到这一 tick 时那个区块可能已经卸载了 —— 所以这不是"跨区块边界"的问题，是**时序**问题。
- **⚠️ `EntityHolderRebarBlock` 的"零实体"地雷（必读）。**
  `ElectricRebarBlock` 继承了 `EntityHolderRebarBlock`，所以**任何电力方块都会被这张表盯上**。
  内部 `holders` 是 `getOrPut` 惰性初始化的：只有"读过 `heldEntities`"或"反序列化过"才会登记本方块。
  而序列化是 `holders[block]?.let { ... }`（null 就什么都不写），
  反序列化是 `... ?: error("Held entities not found for ${block.key}")`（没写就抛）。
  **结论：一个展示实体都不持有的方块，必须在 `postInitialise` / `postLoad` 里主动读一次 `heldEntities`**，
  否则存盘不写键、下次加载直接崩。本项目用 `ensureHolderRegistered()` 处理。
  只持有实体的方块（比如用了 `addElectricPort`）不受影响 —— 因为 `addEntity` 会顺带初始化。
- **物品材质必须和方块材质完全一致**，否则放置时 Rebar 直接抛：
  `IllegalStateException: Item X places block X and so must have the same type`。
  所以"不可见底块 + 好看物品"的正确写法是：
  **物品也用 `STRUCTURE_VOID`，再用 `ITEM_MODEL` 数据组件指定背包外观**
  （Pylon 的 `cargo_duct` 就是 `ItemStackBuilder.rebar(Material.STRUCTURE_VOID, key)
  .set(DataComponentTypes.ITEM_MODEL, Material.GRAY_CONCRETE.getKey())`）。
- **想让自定义方块有"造型"，底块必须用不可见的 `Material.STRUCTURE_VOID`。**
  Rebar 的方块外观走 `BlockTextureEntity`（packet 级展示实体）。
  如果底块是实体方块（混凝土之类），展示实体永远被它盖住，你看到的还是那个方块。
  Pylon 的 `cargo_duct` 就是 `Material.STRUCTURE_VOID`。
  ⚠️ 代价：没开 `customBlockTextures` 的玩家会**什么都看不见**（管道完全隐形）。
  物品材质可以和方块不同 —— 物品是拿在手里的样子，方块是放下去的样子。
- **管道造型不要用 `BlockTextureEntity`，要用真实 `ItemDisplay` + `LineBuilder` 画线。**
  这是本项目踩过的最大的一个坑。`BlockTextureEntity`（packet 方块贴图）那条路：
  - 需要**资源包**才有造型，没资源包就是一个方块；
  - 更糟的是它容易被实体方块遮挡，**被遮挡时亮度为 0，渲染出来是一坨黑混凝土**
    （Pylon 源码原话：*occlude the display entity and cause it to render with brightness 0*）。
  Pylon 的 `CargoDuct` 的做法是 `disableBlockTextureEntity = true`，然后：
  ```kotlin
  ItemDisplayBuilder()
      .transformation(LineBuilder().from(a).to(b).thickness(0.3495f).extraLength(0.3495f).build())
      .itemStack(ItemStackBuilder.of(Material.GRAY_CONCRETE).addCustomModelDataString("$key:line"))
      .build(spawnLocation)
  ```
  **线条本身就是管道形状，不需要资源包。** `LineBuilder.from/to` 直接收 Bukkit `Vector`。
  粗细取 0.3495 一类的小数（Pylon 用 0.3495/0.3490/0.3485 三个值防共面闪烁）。
  本项目简化成"每段线由坐标较小的一端画"，避免多端共享同一个实体带来的清理复杂度。
- **`disableBlockTextureEntity` 是 `RebarBlock` 上的 `open var`**，直接 `override var ... = true` 即可。
- **`RebarBlock` 本体就是注册时给的那个原版方块**，原版机制会认它。
- **tick 是 opt-in 的**：`RebarBlock` 自己**不 tick**，只有实现 `TickingRebarBlock` 才 tick。
  管段**绝对不要**实现它 —— 这是万级设计白送的红利。
- **设置**：`getSettingOrThrow("key", ConfigAdapter.X)` 读 `resources/settings/<内容键>.yml`。
  **文件不存在会直接抛异常**，新增设置记得同步建文件。数值不要写死在代码里。
- **语言**：`lang/en.yml` 和 `lang/zh.yml` 的 key **必须完全一致**（有单测兜底）。
  显示名和描述放 lang；`getPlaceholders()` 的名字要和 `%xxx%` 对上。
- **改显示名不要动 key**：`PipezKeys` 里的 key 一旦发布就别改。

## 发布流程

1. 改 `gradle.properties` 的 `version`
2. 提交（见下面的提交约定）
3. `git tag -a v0.1.0 -m "v0.1.0"` → `git push origin main v0.1.0`
4. CI 自动构建并发布 GitHub Release（`.github/workflows/build.yml` 里 `on.push.tags: v*`）

注意：**CI 不会在普通 push 时构建**（私有仓库要省 Actions 分钟），日常验证就本地跑
`./gradlew build`；需要出包时推标签，或在 GitHub Actions 页面手动触发。

## 提交信息约定

中文，正文按「做了什么 / 为什么 / 内容 / 影响面 / 验证」组织：

```
feat(0.1.0): 一句话说清改了什么

做了什么
- ...

为什么
- ...

内容
- ...

影响面
- 需要重新放置已有方块 / 数值变化 / 无

验证
- ./gradlew build 通过
- 新增或更新了哪些单测
```

## 本机 git 备注（换了机器可删）

这台机器上 `git push/fetch` 访问 github.com 会因 schannel 吊销检查失败
（`CRYPT_E_NO_REVOCATION_CHECK`）。仓库操作优先用 `gh`；需要 `git push` 时：

```bash
git -c http.sslBackend=openssl -c http.sslCAInfo=C:/Users/mc506lw/.workbuddy-ai/win-ca.pem push
```

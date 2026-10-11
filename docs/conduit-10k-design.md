# 万级管道架构设计（Conduit 10k）

> 目标：在 Rebar 上做一套支持 **能量 / 流体 / 物品** 三介质的方块式管道网络，
> **稳态 10,000+ 管段不卡**，且不魔改 Rebar、不走非公开 API。
>
> 本文是设计文档，不是实现。所有 API 断言都来自 rebar `0.44.4-26.2` 的字节码核对。

---

## 1. 目标与非目标

**目标**

- 10,000 管段规模下，稳态（网络空闲）CPU ≈ 0，且不随管段数线性增长。
- 拓扑变更（放置/破坏）的开销与**受影响范围**成正比，不与网络规模成正比。
- 区块加载/卸载不产生 IO 尖峰。
- 三介质统一：同一套管段方块，端点决定它跑什么。

**非目标**

- 不做 Gas（Paper 无 Mekanism，没有连接对象）。
- 不追求 Pipez 的"万级 + 频繁增删仍丝滑"；本文的取舍是**静态万级绝对可达，动态万级受限于区块加载**（见 §9）。

---

## 2. 一句话原则

> **把"数量"和"成本"解耦：管段可以无限多，但每个管段的固定成本必须是常数且趋近于零；
> 所有昂贵的东西——状态、节点、连接点、tick——只挂在少数端点上。**

这条原则直接来自 Pipez：*"Only extracting pipes have block entities."*
Pipez 的管子本身什么都不是，只有抽取端是实体。我们要复刻的是这个**成本结构**，不是它的代码。

---

## 3. 三个核心决策

### 决策 A：管段形状**不持久化**，由邻居推导

管段长什么样（直 / 弯 / 三通 / 十字、朝向），**完全由它周围 6 个邻居决定**，
不需要存进 PDC，也不需要存进 blockstate。

- 好处：`write(pdc)` 不写任何自定义字段 → **PDC 净荷 = 0**。
- 好处：区块加载时**不需要解析任何状态**，形状在需要时按邻居现推（O(6)）。
- 代价：邻居区块未加载时形状可能算错 → 需要"待刷新"标记，区块加载后修正（§6.4）。

> 这是整个设计的支点。只要形状不落盘，"万级"里最大的一块成本（序列化 + 反序列化）直接消失。

### 决策 B：**一个管段 = 一个连接节点**，不是六个端口

能量侧最容易踩的坑是"每个面一个端口"。按现接线盒的写法（`createSimpleElectricPort` × 6），
10,000 管段 = **60,000 个端口节点 + 60,000 个端口展示实体**，直接崩。

正确做法：每个管段方块持有**恰好一个** `ElectricConnectorNode`：

```kotlin
// 构造器是 public 的，可以造"裸节点"，不产生端口实体
ElectricConnectorNode("conduit", blockPosition)
```

- 10,000 管段 = 10,000 个节点、约 30,000 条边（每段实际连 2–4 个邻居）。
- 邻居变化时用 `ElectricNode.connect()` / `disconnectFrom()` 增删**边**。
- ⚠️ 节点进了 PDC **没有 API 能删**，所以节点**只在 `postInitialise()` 建一次**，
  `postLoad()` 用 `getElectricNode("conduit")` 取回。动态变化的只有边（边可增删）。

### 决策 C：连接点只在"端点与交汇处"

流体侧 `FluidManager.tick(segment)` 每 tick 会遍历该 segment 的**所有点**。
如果每个管段都建一个 `VirtualFluidPoint`，10,000 管段就是每 tick 扫 10,000 个点。

规则：**只有当管段的"度 ≠ 2"，或它贴着机器时，才建连接点。**
一段 16 格长直管 = 2 个点，中间 14 格不建点。
`tick(segment)` 的遍历量因此与"端点 + 交汇"成正比，与"管段长度"无关。

---

## 4. 分层

```
┌─────────────────────────────────────────────────────────┐
│  端点层 Endpoint（少、重、有状态、tick）                   │
│  抽取端 / 插入端 / 过滤器 / 升级模块 / 每面配置 GUI          │
│  实现 TickingRebarBlock，持有 LogisticGroup / 连接点        │
└─────────────────────────────────────────────────────────┘
              ▲ 只在端点与机器交互
┌─────────────────────────────────────────────────────────┐
│  网络层 Network（每介质一套）                              │
│  能量：ElectricNode.connect 铺边                          │
│  流体：FluidManager.connect 连段                          │
│  物品：LogisticGroup + CargoRoutes 路由                   │
└─────────────────────────────────────────────────────────┘
              ▲ 只增删边，不搬运
┌─────────────────────────────────────────────────────────┐
│  管段层 Conduit（多、轻、无状态、不 tick）                  │
│  1 个 RebarBlock 对象 + 1 个 connector 节点 + 0 PDC 净荷   │
│  形状由邻居推导；渲染走贪心合并                            │
└─────────────────────────────────────────────────────────┘
```

---

## 5. 数据模型

### 每个管段存什么

| 项 | 值 | 说明 |
|---|---|---|
| RebarBlock 对象 | 1 个 | 框架要求，无法省（兜底方案见 §10） |
| `ElectricConnectorNode` | 1 个 | 决策 B |
| 自定义 PDC 字段 | **0** | 决策 A |
| tick | **无** | 不实现 `TickingRebarBlock` |
| 展示实体 | ~1/16 个 | 16 格直管共用一个（贪心合并） |

**10,000 管段的内存估算**：约 10,000 × (对象 ~300B + 节点 ~200B) ≈ **5 MB**。

### 形状推导

```kotlin
// 6 bit 掩码：bit0=UP bit1=DOWN bit2=NORTH bit3=SOUTH bit4=EAST bit5=WEST
fun shapeMask(): Int =
    FACES.foldIndexed(0) { i, acc, face ->
        if (connectsTo(block.getRelative(face))) acc or (1 shl i) else acc
    }

fun connectsTo(neighbor: Block): Boolean {
    val rebar = BlockStorage.get(neighbor) ?: return false
    return rebar is ConduitBlock || rebar is ConduitEndpoint
}
```

掩码 → 模型/展示实体。掩码是**纯函数**，可以单测（符合项目"换算逻辑抽纯函数"的约定）。

---

## 6. 生命周期

### 6.1 放置
1. `placeBlock` 建 RebarBlock → `postInitialise()`：
   - 建 `ElectricConnectorNode("conduit", pos)`（只此一次）
   - 把 6 个邻居（及自己）塞进 `dirty` 集合
2. tick 末统一处理 `dirty`（§7）

### 6.2 破坏
1. `disconnectAll()` 断开所有边
2. 6 个邻居塞进 `dirty`
3. 展示实体按贪心合并组回收

### 6.3 区块卸载
- 什么都不做。形状不落盘，所以**没有可丢的状态**。
- 展示实体随区块自然回收。

### 6.4 区块加载
- **不解析 PDC、不做 IO**。
- 形状在第一次需要渲染时现推（O(6) 邻居查询）。
- 邻居区块未加载 → 该方向**假定连通**，并登记一个"待刷新"标记；
  邻居区块加载后，把交界处的管段重新塞进 `dirty`。
  （参考 `CargoDuct.postLoad()` 里 `EntityStorage.whenEntityLoads` 的做法。）

### 6.5 邻居变化（放置/破坏/活塞/爆炸/世界编辑）
- **不立即重算**，只把受影响的管段坐标塞进 `dirty` 集合。
- tick 末统一 flush 一次。

---

## 7. 批处理与去抖（关键）

```kotlin
private val dirty = HashSet<BlockPosition>()

fun markDirty(pos: BlockPosition) { dirty += pos }

// 每个服务器 tick 末尾跑一次
fun flush() {
    if (dirty.isEmpty()) return
    val batch = dirty.toList(); dirty.clear()
    for (pos in batch) {
        val c = BlockStorage.getAs<ConduitBlock>(pos) ?: continue
        c.updateShape()        // 更新展示实体
        c.refreshLinks()       // 增删网络边（幂等：先比差集再动）
    }
}
```

**为什么必须批处理**：
- Rebar 的 `ElectricNetwork.addNode/removeNode` 会把 `distances` 置 `null`，
  下一次电力分配时触发一次 BFS 重算。如果在 tick 中反复触发，就是反复重算。
- 批处理保证**一个 tick 内无论改了多少管段，最多触发一次重算**。
- `refreshLinks()` 必须**幂等**：算出目标边集合，与现有边求差集后再增删，
  避免"先全断再全连"引发无谓的 `markDirty`。

---

## 8. 渲染

- 用 `GroupCulledRebarBlock` / `EntityGroupCulledRebarBlock`（Rebar 内建，`FluidPipe` 与 `CargoDuct` 就是这么做的）：
  **多个管段共用一个展示实体**（贪心合并），整组一起剔除。
- 剔除交给 `BlockCullingEngine`（已内建，有 `CullingPreset`）。
- 一段 16 格直管：**1 个长展示实体**（底料）+ 若干小条（标识色），不是 16 个方块实体。
- 拐弯 / 三通 / 十字：各自一个模型段，切换靠形状掩码。
- 也可以走 `RebarBlock` 的 packet 渲染（`BlockTextureEntity`，纯客户端、无服务端实体），
  但它按方块渲染，不适合长管合并——**推荐贪心合并那条路**。

---

## 9. 成本核算（10,000 管段）

| 成本项 | 万级下的量 | 是否随规模线性增长 | 状态 |
|---|---|---|---|
| 渲染 | 约 600–1,000 个展示实体（合并后） | 否（按"段"计） | ✅ 已解决 |
| 管段对象 | 10,000 个 RebarBlock | 是，但常数极小（~5 MB） | ✅ 可控 |
| PDC 序列化 | **0 自定义字段** | 否 | ✅ 已解决 |
| 稳态 tick | 管段 0 tick；只有端点 tick | 否 | ✅ 已解决 |
| 能量重算 | BFS，只从机器出发；惰性 + 快照缓存 | 是，但只在拓扑变更时 | ✅ 可控 |
| 流体 tick | 只遍历端点/交汇点，与管长无关 | 否 | ✅ 可控 |
| 区块加载 | 每区块 ~数百个对象创建，**无 IO** | 是 | ⚠️ 唯一尖峰 |
| 拓扑变更 | 局部 O(6) + 一次批量 flush | 否 | ✅ 可控 |

### 为什么"绝对可达"

稳态下，10,000 管段对服务器的**每 tick 成本 ≈ 0**：
管段不 tick、不搬东西、不持有状态；渲染被合并；PDC 里没有东西。
所有持续性的工作（抽取、插入、过滤）都只发生在**少数端点**上。

所以万级的瓶颈不是"持续开销"，而是两个**瞬态**：
1. 区块加载时创建 RebarBlock 对象；
2. 拓扑变更时的一次网络重算。

这两个都可以被 §7 的批处理和 §10 的兜底压到可接受范围。

**结论：静态万级（建好之后不怎么动）绝对可达。**
**动态万级（边建边拆）取决于区块加载频率，不保证丝滑——这是 Rebar 的框架边界，不是设计缺陷。**

---

## 10. 兜底方案（如果 10,000 个 RebarBlock 仍然太多）

把管段**彻底移出 RebarBlock**：

- 管段 = 普通原版方块（`Material.STRUCTURE_VOID` 之类不可见底料），
  是不是管道由"材质 + 每区块一个位置位图（chunk PDC 里的 `BitSet`）"判断。
- 形状仍由邻居推导；连接仍由节点/点表达（节点可以挂在端点侧）。
- 收益：**零对象、零逐方块 PDC**，chunk 里只有一个 `BitSet`。
- 代价：失去 Rebar 的方块生命周期（放置/破坏/WAILA/掉落/事件全要自己接）。

**建议**：先按 §3–§9 实现（留在框架内），
只有当实测发现"区块加载对象创建"确实是瓶颈时，再降级到本方案。
不要一上来就降级——框架内能解决就别自己造。

---

## 11. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | 管段方块 + 形状推导 + 贪心合并渲染 | 1,000 管段稳态 20 TPS |
| M2 | 能量网络（决策 B）+ 批处理 flush | 10,000 管段静态网络 20 TPS |
| M3 | 端点（抽取/插入）+ 升级模块 | 10 台机器在万级网络上正常搬运 |
| M4 | 流体网络（决策 C） | 长直管段 tick 成本与管长无关 |
| M5 | 物品网络 + 过滤器 | 万级 + 完整过滤器 |
| M6 | 压力测试：10k / 20k / 区块反复加载 | 记录尖峰曲线，决定是否走 §10 |

---

## 附：本设计引用的 Rebar API（均已核对）

| API | 用途 | 可见性 |
|---|---|---|
| `ElectricConnectorNode(name, BlockPosition)` | 每管段一个裸节点 | public 构造器 |
| `ElectricNode.connect/disconnectFrom/disconnectAll` | 增删边 | public |
| `ElectricRebarBlock.addElectricNode(node)` | 挂节点 | default |
| `ElectricNetwork` 的 `distances` 惰性重算 | 拓扑变更开销 | 内部，但可观测 |
| `VirtualFluidPoint(Block, FluidPointType)` | 端点/交汇点 | public 构造器 |
| `FluidManager.add/connect/disconnect` | 流体分段 | public static |
| `LogisticRebarBlock.createLogisticGroup` | 端点物流组 | default |
| `EntityGroupCulledRebarBlock` / `GroupCulledRebarBlock` | 贪心合并 + 成组剔除 | 接口 |
| `BlockCullingEngine` / `CullingPreset` | 剔除引擎 | 内建 |
| `TickingRebarBlock` | **只在端点上实现** | opt-in |
| `ElectricNetwork.Edge(from, to)` | 写 `powerLimit` 给管段限流 | public 构造器 + public setter |

---

## 12. 实现中发现的修正

写代码时核对源码，发现两处要修正本文前面的说法：

### 12.1 "PDC 净荷 = 0" 不完全成立

`ElectricRebarBlock` 的伴生对象里有一个 `@ApiStatus.Internal` 的 `onSerialize`，
它会把方块的所有节点写进 PDC（`NODES_KEY`，每个节点含 id / name / type / **connections**）。

所以：
- **自定义字段**确实是 0（形状不落盘）。
- 但**框架会为电力节点写一份**：每管段 1 个 `ElectricConnectorNode`，
  带约 3 个连接 UUID ≈ 100 字节/管段 → 一万管段约 1 MB。

想连这 1 MB 也省掉，只有一条路：**不用 `ElectricRebarBlock`**，
自己调 `ElectricityManager.addNode/removeNode` 管理节点、连接完全从邻接重建、不落盘。
代价是要自己处理区块加载/卸载的生命周期（见 §10 兜底方案）。

**结论：M2 先用框架方案（1 MB 可接受），把"自管节点"列为 M6 压测后的可选优化。**

### 12.2 破坏时 Rebar 会清理连接（好消息）

`ElectricRebarBlock` 的伴生对象监听 `RebarBlockBreakEvent`，
在方块被拆时对每个节点执行 `disconnectAll()` + `ElectricityManager.removeNode()`。

所以**不会留下悬空连接**，管段不需要像 `Lapis-Cable` 的 `WireCleanup` 那样定期扫脏数据。
（电线的问题在 `WireEntity`，那是实体不是节点，两者不同。）

### 12.3 一个管段一个节点是可行的

`ElectricConnectorNode(String name, BlockPosition)` 是 **public 构造器**，
可以造"裸节点"（不产生端口展示实体）。这正是决策 B 的落地方式。

---

## 13. 实现状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 管段方块 + 形状推导（纯函数 + 单测） | ✅ 已完成 |
| M2 | 能量网络（单节点/管段）+ 批处理 flush + 边限流 | ✅ 已完成 |
| M2.5 | 扳手（按面禁用）+ 邻接事件接入 | ✅ 已完成 |
| M3 | 端点（抽取/插入）+ 升级模块 | ⬜ 待做 |
| M4 | 流体网络（决策 C：只在端点/交汇建点） | ⬜ 待做 |
| M5 | 物品网络 + 过滤器 | ⬜ 待做 |
| M6 | 贪心合并渲染 + 10k/20k 压测 + 自管节点优化 | ⬜ 待做 |

**已知限制（v0.1）**
- 渲染还是原版方块外观，连接形状只算出来、没有落到模型上（M6）。
- 机器互通是 best-effort：靠端口展示实体的世界坐标反推面，端口实体未加载时会延迟到下次 flush。
- 没有端点和档位，管段本身只有 `config.yml` 里一个全局容量。

---

## 14. 实现中发现：流体网络对第三方插件是封闭的（决策 C 作废）

写流体实现时编译器直接报错：

```
Cannot access 'object FluidManager : Any': it is internal in file.
```

去源码核对，确认了两个关键类型在 **Kotlin 层是 `internal`**：

| 类型 | Kotlin 可见性 | 后果 |
|---|---|---|
| `io.github.pylonmc.rebar.fluid.FluidManager` | `internal object` | 无法 `add` / `remove` / `connect` / `disconnect` 流体点 |
| `io.github.pylonmc.rebar.fluid.placement.FluidPipePlacementService` | `internal object` | 无法让两点建立连接 |
| `VirtualFluidPoint` | public | 能构造，但**没有公开手段把它接进网络** |
| `FluidPointType` | public | 同上 |
| `FluidRebarBlock.createFluidPoint(...)` | public | 能建点（会顺带建展示实体），但**连不起来** |

**结论：用公开 API 无法组建"方块式流体网络"。**
流体点之间的连接只有 `FluidPipePlacementService` 能做，而它是 `internal`；
玩家用 `FluidPipe` 物品拉线（Rebar 官方路径）才会走到那里 —— 那正是我们想替代的点对点模型。

所以：

- **公开 API 路线作废**。想用公开 API 组建流体网络，Rebar 目前不支持。
- 但**反射路线可行**，而且比预想干净：`FluidManager` 的方法在字节码里是
  `public static final`，而且**没有名字混淆**（是 `add` / `connect` / `disconnect`，
  不是 `add$rebar`），所以 `Class.forName` + `getMethod` 就能直接调到。

**最终采用：隔离式反射桥（`fluid/FluidBridge.kt`）**

| 措施 | 目的 |
|---|---|
| 所有反射集中在**一个文件** | 换 API / 出问题时只改一处 |
| 启动时一次性解析并缓存 `Method` | 不在 tick 热路径里做反射查找 |
| 任何一步失败只关掉流体，**不抛异常** | 流体坏掉不拖垮能量与物品 |
| `config.yml` 的 `fluid.use-reflection` 开关 | 想完全不用内部 API 就设 false |
| 启动日志明确标注"unsupported API" | 提醒 Rebar 升级后必须重新验证 |

> 这是本次调研里最有价值的一条结论：**能量与物品的传输 API 是公开的，流体不是。**
> 想在 Rebar 上做"三合一 Universal 管道"，流体这一环必须接受"走内部 API"的代价。

### 14.1 同样的问题也出现在物流侧

写物品端点时踩到了同一类坑 —— 下面这些在 release 版里从 Kotlin 侧**全都取不到**：

| 类型 | 后果 |
|---|---|
| `logistics.LogisticGroup` | 取不到物流组 |
| `logistics.slot.LogisticSlot` | 取不到物流槽 |
| `block.interfaces.LogisticRebarBlock.logisticGroups` | 同上 |
| `block.interfaces.VirtualInventoryRebarBlock.getVirtualInventories` | 取不到 Rebar 机器的虚拟库存 |

**注意**：`LogisticSlot` 在 GitHub 源码 HEAD 里明明是 `public interface`，
但在 `0.44.4-26.2` 这个 release 里就是取不到。
**判断可见性只能靠"写代码编译一次"，不能靠读源码。**

对策：

- 物流组那套**完全不用**，物品端点改走**原版 `InventoryHolder`**（纯公开 API）。
- Rebar 机器的虚拟库存用**第二个反射桥** `bridge/VirtualInventoryBridge.kt` 取
  `getVirtualInventories()`；拿到 `VirtualInventory` 之后，
  所有读写都走 InvUI 的公开 API（`getItem` / `setItem(UpdateReason, slot, stack)`），
  不再需要反射。

**最终形态**：能量 = 纯公开 API；物品 = 公开 API + 一条反射（只为拿库存对象）；
流体 = 全程反射。三块里只有能量是"干净"的。

---

## 15. 实现状态（更新）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 管段方块 + 形状推导（纯函数 + 单测） | ✅ 已完成 |
| M2 | 能量网络（单节点/管段）+ 批处理 flush + 边限流 | ✅ 已完成 |
| M2.5 | 扳手（按面禁用）+ 邻接事件接入 | ✅ 已完成 |
| M3 | 档位（4 档）+ 升级模块 | ✅ 已完成 |
| M4 | 流体网络（决策 C） | ⚠️ 已完成，但**走反射**（公开 API 不可行，见 §14） |
| M5 | 物品管道 + 相邻容器自动成端 + 中央搬运任务 | ✅ 已完成 |
| M6 | 形状渲染（自定义模型数据） | ✅ 已完成 |
| M6.5 | 贪心合并渲染 / 万级实测 / 自管节点优化 | ⬜ 待做（贪心合并已做，实测待补） |

**物品侧的设计取舍**

不能用 Rebar 的 `CargoRoutes`：它把**任何** `CargoRebarBlock` 当作路由终点
（`if (currentBlock is CargoRebarBlock) { endpoint = ...; break }`），
所以管段链上的第一根会被误判成目的地。而管段也不该实现 `CargoRebarBlock` ——
那个接口的伴生对象会给**每个方块**起一个协程 ticker，直接违背"管段不 tick"。

### 15.1 M5 定稿：没有端点方块，端点 = "抽取面"

最初的 `ItemEndpoint` 方块（`pipez:item_endpoint`）是**死代码** ——
`PipeType` 里压根没有 `ITEM` 条目，所以它调用
`ConduitGraph.findFirst(block, Category.ITEM)` 永远返回 null，**一件物品都搬不动**。
本轮把它整个删掉，换成和 Pipez 一致的模型：

> **没有端点方块。哪根物品管的哪个面贴着容器、且被扳手点成"抽取"，那一面就是一个 (抽取) 端点。**

落地方式：

| 部分 | 位置 | 职责 |
|---|---|---|
| `PipeType.ITEM` | `conduit/PipeType.kt` | 物品管的身份（橙色彩条），独立于能量 / 流体 |
| 容器识别 | `cargo/CargoEndpoint.kt` | 原版 `InventoryHolder` + Rebar 机器虚拟库存，抹平两种读写 API |
| 端点登记 + 搬运 | `cargo/ItemTransfer.kt` | 只登记"抽取面贴容器"的管格；每 tick 只遍历这些端点 |
| 路由 | `conduit/ConduitGraph.kt` | 沿 `Category.ITEM` 做 BFS，能量 / 流体管一律死路 |
| 邻接刷新 | `listeners/ConduitLinkListener.kt` | 增加**原版**容器放置 / 破坏监听（Rebar 事件覆盖不到原版方块） |

**核心约束仍然是"管段不 tick"**：搬运集中在中央任务里，每个端点每 tick 只跑一次 BFS，
管段本身一 tick 都不花。这和 Pipez 的"只有抽取管有 block entity"（`HAS_DATA` 属性）
是同一个思路。

**升级模块对物品管生效**（对齐 Pipez 的能力矩阵）：

| 档位 | 红石 | 分配 | 过滤 |
|---|---|---|---|
| Basic | ✅ | ❌ | ❌ |
| Improved | ✅ | ✅ | ❌ |
| Advanced | ✅ | ✅ | ✅ |
| Ultimate | ✅ | ✅ | ✅ |

也就是说：**物品管也得插升级模块才能用分配 / 过滤**，和能量管一致
（`ConduitTier.canChange*` 已经就是这张表，由 `ConduitTierTest` 锁死）。
流体管则没有升级槽（对齐 Pipez 的 `FluidPipeType.hasUpgrade() == false`）。

### 15.2 修正 §14.1：`LogisticSlot` / `LogisticGroup` 其实取得到

§14.1 说物流那套"从 Kotlin 侧全都取不到"——**这条后来被证伪了**：
在 `0.44.4-26.2` 里 `io.github.pylonmc.rebar.logistics.LogisticGroup` 与
`logistics.slot.LogisticSlot` **都是公开的**，只有 `CargoRoutes` 是 `@ApiStatus.Internal`。

不过 M5 **仍然不使用**它们，原因是语义不匹配而不是可见性：
`LogisticGroup.getVanillaLogisticSlots` 那套是给"货运路由"设计的（按 input/output 分组），
而我们需要的是"整箱搬运 + 自己按分配模式排序"，直接走 `InventoryHolder` /
`VirtualInventory` 更直白。这条仅作澄清，不影响实现。

所以物品路由是自建的：`ConduitGraph.findFirst` 从端点出发沿管段做 BFS，
只走没被扳手禁用的方向，遇到第一个满足条件的非管段方块就返回。
目标每 20 tick 重算一次，避免每 tick 跑 BFS。

容器访问也刻意**绕开了 `LogisticSlot` / `LogisticGroup`** ——
那套 API 在 release 版里从 Kotlin 侧同样取不到。只用两条公开路径：

- Rebar 机器 → `VirtualInventoryRebarBlock` 暴露的 `VirtualInventory`（InvUI，本来就是依赖）
- 原版容器 → `InventoryHolder.inventory`

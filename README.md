# Lapis-Pipez

**方块式多介质管道附属**（Rebar / Paper 26.2）—— 把"线缆"升级成"管道网"：管段自动连成任意拓扑，
不需要接线盒也能分叉、汇聚、拐弯。

## 为什么会有这个

生态里布线是**点对点实体**（Rebar 的 `WireEntity` / `FluidPipe`），想分叉就得摆一个接线盒；
而 Pipez 那种"贴上去就自动连成一张网"的方块式管道，Rebar 生态里没有。

本附属就是补这一块。设计目标是 **稳态 10,000+ 管段不卡**。

## 设计核心

> **把"数量"和"成本"解耦：管段可以无限多，但每个管段的固定成本必须趋近于零；
> 所有昂贵的东西（状态、节点、连接点、tick）只挂在少数端点上。**

三条具体决策：

1. **管段形状不持久化** —— 由六个邻居现推，PDC 里没有自定义字段。
   区块加载零 IO、零反序列化。
2. **一个管段 = 一个连接节点**，不是六个端口。
   每面一个端口的话，一万管段就是六万节点 + 六万展示实体。
3. **批处理** —— 邻居变化只登记，tick 末统一 flush，保证一个 tick 内最多触发一次网络重算。

完整推导、成本核算、兜底方案见 **[docs/conduit-10k-design.md](./docs/conduit-10k-design.md)**。

## 内容

| 内容 | 键 | 说明 |
|---|---|---|
| 管道 | `pipez:conduit` | 放置后自动连接相邻管道与机器端口 |
| 管道扳手 | `pipez:wrench` | 右键管道的某个面，开关那个方向的连接 |
| 物品端点 | `pipez:item_endpoint` | 扳手切换 无 / 抽取 / 接收；潜行 + 右键物品设白名单 |
| 升级模块 ×4 | `pipez:upgrade_basic` … `ultimate` | 右键管段提档：1024 / 4096 / 16384 / 65536 W |

## 用法

1. `/rb give @s pipez:conduit`
2. 一根一根摆下去，相邻的会自动连起来；拐弯、三通、十字都不用额外方块
3. 贴着机器的端口放，管道会自动接到那个端口上
4. 用 `pipez:wrench` 右键某个面，可以单独断开那个方向
5. 传输不够就用 `pipez:upgrade_*` 右键管段提档
6. 要搬物品：在箱子旁边放 `pipez:item_endpoint`，扳手切成"抽取"，
   在另一边的箱子旁再放一个切成"接收"，中间用管道连起来

## 配置

首次启动生成 `config.yml`：

```yaml
conduit:
  capacity-multiplier: 1.0    # 所有档位容量都乘它
  flush-interval-ticks: 1     # 批处理间隔，调大可以削峰但有延迟
tiers:
  basic: 1024.0               # 各档位基础容量（瓦特）
  improved: 4096.0
  advanced: 16384.0
  ultimate: 65536.0
cargo:
  items-per-tick: 8           # 物品端点每 tick 搬运量
fluid:
  use-reflection: true        # ⚠️ 见下
  per-second: 1000.0
render:
  shape-models: true          # 形状写进自定义模型数据（需资源包）
```

## ⚠️ 关于反射

**Rebar 有一大批 API 在 Kotlin 层是 `internal`**，`javap` 看着是 `public` 但编译器不让用。
已确认取不到的包括：`FluidManager`、`FluidPipePlacementService`、`LogisticGroup`、
`LogisticSlot`、`VirtualInventoryRebarBlock.virtualInventories`。

所以本附属有**两块功能必须走反射**：

| 功能 | 反射目标 | 不做会怎样 |
|---|---|---|
| 流体网络 | `FluidManager` 的 `add`/`connect`/`disconnect` | 完全没有流体 |
| Rebar 机器库存 | `VirtualInventoryRebarBlock.getVirtualInventories` | 物品端点只认原版容器 |

隔离措施：

- 全部反射集中在 `bridge/` 两个文件（`FluidBridge` / `VirtualInventoryBridge`）
- 启动时解析一次并缓存 `Method`，**不在 tick 热路径里做查找**
- 任何一步失败**只关掉对应功能**，绝不抛异常影响其它功能
- `bridge.use-reflection: false` 可整体关闭
- 启动日志明确标注这是 unsupported API

Rebar 是 0.x，内部 API 随时可能变；**每次升级 Rebar 后都要重新验证**。

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 管段方块 + 形状推导（纯函数 + 单测） | ✅ |
| M2 | 能量网络（单节点/管段）+ 批处理 + 边限流 | ✅ |
| M2.5 | 扳手 + 邻接事件接入 | ✅ |
| M3 | 档位（4 档）+ 升级模块 | ✅ |
| M4 | 流体网络 | ⚠️ 已完成，走反射 |
| M5 | 物品端点 + 路由 + 白名单 | ✅ 原版容器 + Rebar 机器 |
| M6 | 形状渲染（自定义模型数据） | ✅ |
| M6.5 | 贪心合并渲染 / 万级实测 | ⬜ |

**已知限制**

- 流体管道**没有可视化**：Rebar 的管道渲染在 `FluidPipePlacementService`（也是 internal），
  流体能流但看不到贴图。
- 管道的底块是**不可见的 `STRUCTURE_VOID`**（和 Pylon 的货运管道一样），
  造型由**真实 `ItemDisplay` + `LineBuilder` 画线**实现 ——
  **不需要资源包**，看起来就是一根细管。
  （不用 Rebar 的 `BlockTextureEntity`：那条路要资源包，而且被遮挡时亮度为 0、会渲染成黑块。）
- 目前**每段线一个展示实体**，还没有做 Pylon 那样的"整条直线合并成一个实体"（M6.5）。
- 物品端点靠"相邻方块自动识别"找容器，没有指定面的配置。

## 构建

```bash
./gradlew build      # → build/libs/Lapis-Pipez-0.1.3.jar
```

前置：MC 26.2（Paper 或其分支）+ Rebar 0.44.4+ + JDK 25。

> 开发相关（怎么查 Rebar / Pylon 源码、踩过的坑、提交约定）都在 [AGENTS.md](./AGENTS.md)。

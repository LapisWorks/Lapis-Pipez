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
| 能量管道 | `pipez:pipe_energy` | 红色彩条；自动连相邻能量管与机器电口，上限 8192 W（可升级） |
| 流体管道 | `pipez:pipe_fluid` | 蓝色彩条；自动连相邻流体管与机器流体口，速率见 `config.yml` |
| 物品管道 | `pipez:pipe_item` | 橙色彩条；自动连相邻物品管与**容器**，贴着容器的面即为端点 |
| 管道扳手 | `pipez:wrench` | 潜行 + 右键某个面：普通 → 抽取 → 断路 循环 |
| 升级模块 ×4 | `pipez:upgrade_basic` … `ultimate` | 右键管段的抽取面插模块：8192 / 16384 / 32768 / 73728 W，并解锁界面功能 |

## 用法

### 布线（能量 / 流体 / 物品通用）

1. `/rb give @s pipez:pipe_energy`（或 `pipe_fluid` / `pipe_item`）
2. 一根一根摆下去，相邻的**同类型**管道会自动连起来；拐弯、三通、十字都不用额外方块
3. 贴着机器的端口（或容器）放，管道会自动接到那一面
4. 用 `pipez:wrench` **潜行 + 右键**某个面，在 `普通 → 抽取 → 断路` 之间循环

### 搬物品（没有端点方块，靠"抽取面"）

物品管**不 tick**，搬运由一个中央任务驱动，只跑少数"抽取端点"：

1. 从箱子 A 拉一根物品管到箱子 B（中间随意拐弯 / 分叉）
2. 潜行 + 扳手右键**贴箱子 A 的那一面** → 切成 **抽取**
3. 箱子 B 那边**保持默认（普通）**即可 —— 它会自动接收
4. 想过滤：拿着升级模块右键那个抽取面插上（**基础=红石 / 强化=分配 / 高级=过滤**），
   再普通右键那个抽取面打开配置界面

> 一根管可以一个面抽、另一个面送；多个抽取端点会给管网叠加吞吐。

## 配置

首次启动生成 `config.yml`：

```yaml
conduit:
  capacity-multiplier: 1.0    # 所有档位容量都乘它
  tier-power-limit: true      # 是否把档位容量写成边的过流上限（见下方警告）
  flush-interval-ticks: 1     # 批处理间隔，调大可以削峰但有延迟
cargo:
  items-per-tick: 8           # 每个抽取端点每 tick 搬运量
fluid:
  per-second: 1000.0          # 流体速率（mB/s）
bridge:
  use-reflection: true        # ⚠️ 见下方「关于反射」
render:
  stripes: true               # 彩条（纯装饰，占每臂实体数的 2/3）
  bands: true                 # 彩箍（纯装饰，大型管网里数量最多）
```

各档容量固定在代码里（`ConduitTier`）：**8192 / 16384 / 32768 / 73728 W** ——
和 `Lapis-Cable` 的"1 个下界合金锭 = 8192 W"用同一把尺子。

## ⚠️ 关于反射

**Rebar 有一大批 API 在 Kotlin 层是 `internal`**，`javap` 看着是 `public` 但编译器不让用。
已确认取不到的包括：`FluidManager`、`FluidPipePlacementService`、`LogisticGroup`、
`LogisticSlot`、`VirtualInventoryRebarBlock.virtualInventories`。

所以本附属有**两块功能必须走反射**：

| 功能 | 反射目标 | 不做会怎样 |
|---|---|---|
| 流体网络 | `FluidManager` 的 `add`/`connect`/`disconnect` | 完全没有流体 |
| Rebar 机器库存 | `VirtualInventoryRebarBlock.getVirtualInventories` | 物品管道只认原版容器 |

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
| M5 | 物品管道 + 相邻容器自动成端 + 中央搬运任务 | ✅ 原版容器 + Rebar 机器 |
| M6 | 形状渲染（自定义模型数据） | ✅ |
| M6.5 | 贪心合并渲染 / 万级实测 | ✅ 已做贪心合并，万级实测待补 |

**已知限制**

- 流体管道**没有可视化**：Rebar 的管道渲染在 `FluidPipePlacementService`（也是 internal），
  流体能流但看不到贴图。
- 管道的底块是**不可见的 `STRUCTURE_VOID`**（和 Pylon 的货运管道一样），
  造型由**真实 `ItemDisplay` + `LineBuilder` 画线**实现 ——
  **不需要资源包**，看起来就是一根细管。
  （不用 Rebar 的 `BlockTextureEntity`：那条路要资源包，而且被遮挡时亮度为 0、会渲染成黑块。）
- 物品搬运的**接收容器按"整箱"收**，不会优先塞进"已有同类物品"的槽（原版漏斗那种）。
- 物品管**不支持堆叠升级 / 每槽定量**（Pipez 的 Stack Upgrade 未实现）。

## 构建

```bash
./gradlew build      # → build/libs/Lapis-Pipez-<版本>.jar
```

前置：MC 26.2（Paper 或其分支）+ Rebar 0.44.4+ + JDK 25。

> 开发相关（怎么查 Rebar / Pylon 源码、踩过的坑、提交约定）都在 [AGENTS.md](./AGENTS.md)。

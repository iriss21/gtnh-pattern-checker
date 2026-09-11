# Pattern Checker — GTNH 2.8.4 移植版

[3QQQ/PatternChecker](https://github.com/3QQQ/PatternChecker)（原为 NeoForge 1.21.1 AE2 附属）向 GTNH 2.8.4（Minecraft 1.7.10 + Forge 10.13.4.1614 + AE2 Unofficial rv3）的移植。

## v1.5.0

| 问题 | 根因与修复 |
|---|---|
| 编辑样板里的 ÷10 / ÷2 / −1 按钮「无效」 | 这三个按钮改的是**延迟生效的全局倍率**，而倍率下限是 1：默认值就是 1，所以按下去数字纹丝不动，看起来像坏键。现在六个按钮（÷10 ÷2 −1 +1 ×2 ×10）**直接、立即作用于所有已填槽位的数量**（下限 1，上限 10¹⁵），效果当场可见；单槽微调仍用滚轮。上传时不再附带额外倍率 |
| 需要「忽略」功能，被忽略的样板不再占用主面板 | 新增按**样板内容指纹**（`Item.getIdFromItem:damage:FNV64(规范化 NBT)`，规范化 = 键排序 + 跳过 `author`，因此跨重启稳定）记录的忽略表，按玩家保存在 `config/patternchecker-ignored.txt`。面板底部新增 **忽略 / 取消忽略 / 显示已忽略 (N)** 三个按钮：被忽略样板的报告不计入错误/警告总数、不出现在聊天输出、默认也不在主列表里；点「显示已忽略」可把它们（灰显、带 `[已忽略]` 标记）调出来再取消忽略。重复样板这类分组条目用组合指纹作为 key，同样可忽略 |

## v1.4.1

| 问题 | 根因与修复 |
|---|---|
| NEI 物品面板拖拽在编辑器里没反应 | NEI 的投放分发挂在 `GuiContainer.mouseClicked` 上：`NEITransformer` 把 `GuiContainerManager.mouseClicked` 注入到该方法，再由 `LayoutManager.mouseClicked → Widget.handleClickExt → PanelWidget.handleDraggedClick → INEIGuiHandler.handleDragNDrop` 分发。编辑器原先**命中幽灵槽就提前 return、不调 `super.mouseClicked`**，等于把投放需要的那次点击整段吞掉。现在改为**先调 `super.mouseClicked`**，再由 `handleNeiDrag` 置一个"本次点击已由 NEI 处理"的标志，避免随后用空手光标逻辑把刚放进去的物品又清掉 |
| 编辑界面空白槽位闪烁 | 幽灵槽原先画在 `drawGuiContainerBackgroundLayer`。这一层此时 **深度测试仍然开着**（深度缓冲里还留着世界渲染的值），槽位底格与物品图标/数量文字互相争深度，就会闪。改为在 `drawGuiContainerForegroundLayer`（MC 已关掉深度测试）里画，并且分三遍：底格 → 图标（临时开 GUI 物品光照）→ 数字，避免不同阶段互相污染 GL 状态 |

## v1.4.0

| 问题 | 修复 |
|---|---|
| 通配样板（`wildcardpattern`）被误报为「空白样板」 | 这类样板把配置存在自己的 NBT 结构里（不是 AE2 的 `in`/`out`），原先"没有 `in` 就当空白"的判定会直接误报。现在识别为**第三方样板**：计数但跳过逐项校验（只有所属模组才知道怎么展开它），面板摘要与聊天里单独显示数量。判定先看 NBT 标记（`WildcardPattern` / `WildcardInputComponents`），再退回类名匹配，**不需要编译期依赖** |
| 「取出」报「样板供应器不存在或未加载」（AE2FC 二合一接口） | 二合一接口是**线缆上的部件**（`PartFluidInterface extends PartInterface`），`world.getTileEntity()` 拿到的是线缆总线而不是接口，旧代码直接返回 null。现在会从网格节点里找 `IInterfaceHost` 并用「目标槽位是否仍是那个样板」消歧（一根线缆上可能挂多个接口）。**编辑**路径也走同一个解析，因此一并修好 |
| 编辑界面不是九宫格，且不支持 NEI 合成表 | 输入改为 **3×3 九宫格**（slot 顺序与 AE2 一致，row-major），输出 3 格横排在其右侧；数量显示在格子内，悬停显示完整数量和名称。NEI 侧注册了 `crafting` 覆盖层（偏移按 NEI 自身的 `(25, 6)` 网格原点推算），**按 R 后合成表会正好压在九宫格上**；再注册 overlay handler，点合成表的转移按钮即可**一键填入**九宫格 |
| 面板「样板 N 个」与「扫描」按钮重叠 | 摘要行原先画在 y=34，而按钮占 y=22..40。改为按钮下方两行（y=43 / y=52），列表整体下移，底部按钮与"其余 N 条已省略"也不再相互压字 |

顺带修掉：编辑窗口的打开方式改为走 FML 的窗口通道（`getClientGuiElement`），这样容器与 `windowId` 与服务端一致，**编辑器里的玩家背包变成可点击的**；`PacketEditData` 后到时改为**就地刷新**已打开的编辑器，不再重开一个空窗口（原先是竞态，偶发显示空槽）。

## v1.3.0 — GTNH 2.8.4 兼容性修复

针对目标服务器（`GT_New_Horizons_2.8.4_Server`，AE2 `rv3-beta-695-GTNH`）的实测差异做了以下修正：

| 问题 | 修复 |
|---|---|
| 「编码并上传」在 AE2 rv3-beta-695 上会把样板改写坏：旧版 AE2 的 `PatternHelper` 只按 **int 型 `Count`** 读取条目（`Platform.loadItemStackFromNBT`），且没有 `Cnt` 回退，而 `AEItemStack#writeToNBT` 写的是 `Count=0` + `Cnt=long` | 条目改为同时写 vanilla `ItemStack.writeToNBT` + int `Count` + long `Cnt`，新旧两代 AE2 都能正确读取 |
| 编辑器只认物品栈，遇到流体/气体条目（AE2FC、ae2thing 等）会把条目写空 | 扫描到非物品条目时**拒绝打开编辑器**并提示，不再静默丢数据 |
| `reencode()` 只保留 `crafting/substitute/beSubstitute/author`，会丢第三方根级 NBT（`tunnelUuid` 等） | 改为在原始 tag 副本上就地改写 `in`/`out`/`crafting`，其余键全部保留 |
| NEI 物品面板拖拽（1.7.10 没有 JEI，配方查看器是 NEI）拖到编辑器槽位无反应 | 新增 NEI 转发器，实现 `INEIGuiHandler#handleDragNDrop`，拖拽可直接落进 9+3 个幽灵槽 |
| NEI 物品面板盖住检查面板右侧按钮 | 实现 `INEIGuiHandler#hideItemPanelSlot` |
| 编辑器 GUI 高 244 px，在 240 px 逻辑画布（854×480 / 1280×720）上会溢出屏幕 | 压缩到 238 px；同时修掉「输入/输出」标签与提示文字重叠的问题 |
| 包处理器在 Netty 线程直接操作世界/方块实体、直接 `displayGuiScreen` | 服务端与客户端各加一个 tick 队列，工作调度回主线程 |
| `PanelStore` / `EditStore` / `HighlightStore` 非线程安全且玩家退出不清理 | 换成 `ConcurrentHashMap`，并在玩家登出时清理 |

NEI 集成通过反射加载（`Loader.isModLoaded("NotEnoughItems")` 守卫），**没装 NEI 时模组功能完全不受影响**。

## 功能

### 图形化检查面板（v1.1.0）

手持样板检测工具**右键空气**打开面板（自动扫描）：

- 顶部按钮：**扫描**（已绑定/最近网络）、**扫描全部**（本维度所有已加载网络）、**清除高亮**
- 汇总行：样板总数（接口/存储）、错误数、警告数
- 问题列表：滚轮滚动、点击选中，每条显示样板名、位置（接口样板槽 @ 坐标 / ME 存储）和问题文本（错误红 / 警告黄）
- 选中条目后底部第一排按钮可用：**高亮**（世界线框 15 秒）、**编辑**（处理样板）、**取出**（接口样板）
- 底部第二排：**忽略**（把选中样板加入忽略表，同类样板以后一律不报）、**取消忽略**、**显示已忽略 (N)**（开关，被忽略的样板灰显并带 `[已忽略]` 标记，从中可取消忽略）

### 逐件替换编辑器（v1.4.0：3×3 九宫格）

面板中选中一条**接口里的处理样板**点「编辑」，打开幽灵槽编辑器（**3×3 输入九宫格** + 3 输出横排 + 合计行 + 玩家背包）：

- **左键持物点击槽位**：设置该槽物品（数量 = 手上堆叠数）；**空手左键**：清空槽位；**右键同物品**：数量加一手
- **从 NEI 拖入**：按住 NEI 物品面板里的物品拖到九宫格/输出格即可落入
- **NEI 合成表**：悬停物品按 `R` 显示合成表（会与九宫格对齐），点合成表上的转移按钮可**一键填入**输入格与输出格（同物品保留已调好的数量）
- **滚轮悬停调数量**：±1，Shift ±10，单槽上限 10¹⁵（数量显示在格子内，悬停显示完整数量，K/M/G/T 缩写显示）
- **批量数量按钮**：÷10 ÷2 −1 +1 ×2 ×10，**立即作用于所有已填槽位**（v1.5.0 起不再是延迟倍率）；下方合计行显示输入/输出总数
- **编码并上传**：服务端以提交的完整槽位布局重建样板 NBT（`crafting=false`，保留原样板的所有其他 NBT 字段，包括 `substitute`/`beSubstitute`/`author` 及第三方附加键），上传前**重新校验接口槽位仍是原样板**，否则取消
- 关闭窗口（ESC/E）自动发送取消，释放服务端会话；服务端还会拒绝空输入/空输出、槽位数超限（>9×3）以及**含非物品条目**（流体/气体）的提交

安全设计：客户端只发送槽位物品 ID/损伤值/数量/NBT；服务端用 AE2 自己的写入器重建条目、重新校验目标槽位，无刷物品或覆盖错误物品的可能。

### 忽略已审阅的样板（v1.5.0）

面板里选中一条问题样板点「忽略」，该**样板内容**的指纹就会进入忽略表：之后完全相同的样板（AE2FC 接口里的、为并行复制多份的、搬家后换了坐标的……）都不再产生报告。

- 忽略表按玩家保存，服务器端文件为 `config/patternchecker-ignored.txt`（每行 `玩家名<TAB>指纹`），**重启后仍有效**；文件读写失败时仅本次会话生效，不影响其他功能
- 被忽略的样板仍会被扫描、仍计入"样板 N 个"，但**不计入错误/警告数**、不出现在聊天报告里，默认也不在面板列表中
- 「显示已忽略 (N)」开关可把它们调出来（灰显 + `[已忽略]` 标记），选中后点「取消忽略」即可恢复报告
- 指纹 = `物品ID:损伤值:FNV64(规范化 NBT)`；规范化即**按键名排序**遍历 NBT 并跳过会被 AE2 重写的 `author`，所以同一份样板在不同会话、不同遍历顺序下算出的 key 一致
- 重复样板（分组条目）没有单一物品可指，用整组的输入/输出组合指纹作为 key，同样支持忽略

### 扫描检测项

扫描 ME 网络中所有可访问的已编码样板（接口样板槽 + ME 存储单元），在面板/聊天栏报告：

- 无法解析的样板（空白、NBT 损坏、合成配方已失效）
- 被 AE2 标记为 `InvalidPattern` 的样板
- 数量异常（≤0）的输入/输出、无输出样板
- 处理样板的输入=输出自循环
- 输入物品既不在网络存储中、也不可由网络合成（外部供给检测）
- 接口样板未注册到网络合成缓存（接口断电/未激活）
- 编码内容完全相同的重复样板

**不参与逐项校验的样板**（只计数，在摘要里单列）：

- 第三方样板：把配置存在自身 NBT 结构里的样板（如 `wildcardpattern` 的通配样板）。判定依据是 NBT 标记与物品类名，**不需要编译期依赖**；只有所属模组知道如何展开它们。

聊天（命令路径）中每条问题带 **[定位]** 点击链接，世界线框高亮 15 秒。

## 使用

### 样板检测工具

合成配方（与原版模组一致）：

```
铁锭  石英纤维  铁锭
红石   指南针   红石
铁锭  石英纤维  铁锭
```

- **右键 ME 网络方块**（控制器/线缆/接口等）：绑定该网络
- **右键空气**：打开图形化检查面板
- **Shift+右键**：解除绑定

### 命令（权限等级 0，所有玩家可用，聊天报告路径）

```
/patterncheck scan          扫描已绑定/最近网络
/patterncheck scan all      扫描本维度所有已加载网络
/patterncheck bind <x y z>  绑定指定坐标的网络方块
/patterncheck unbind        解除绑定
/patterncheck highlight <n> 高亮第 n 条问题
/patterncheck clear         清除高亮与扫描缓存
```

## 安装

将 `build/libs/patternchecker-1.5.0.jar` 放入**客户端与服务端**的 `mods/` 目录（面板 GUI、编辑/取出、高亮均需两端安装；命令聊天输出仅服务端也可用）。

依赖：GTNH 2.8.4 自带的 `Applied-Energistics-2-Unofficial`（`appliedenergistics2`）。
可选：`NotEnoughItems`（装上后编辑器支持 NEI 物品面板拖拽，并可避免面板遮挡）。

## 构建

需要 JDK 25（Gradle 守护进程）+ GTNH Gradle 工具链（自动下载 JDK 21/8 编译链）：

```bat
set JAVA_HOME=C:\Users\s21\.jdk\jdk-25.0.4.1\jdk-25.0.4.1+1
gradlew build
```

## 与原版 (1.21.1) 的差异

| 原版功能 | 移植情况 |
|---|---|
| 样板扫描/校验核心 | ✅ 已移植（接口样板槽 + ME 存储） |
| 无效/损坏/配方失效/重复样板检测 | ✅ 已移植 |
| 输入供给检测（库存+可合成） | ✅ 已移植 |
| 图形化检查面板（列表/按钮/滚动） | ✅ 已移植（1.7.10 GuiContainer 重写） |
| 样板取出 / 编辑 / 编码上传 | ✅ 已移植（幽灵槽逐件替换 + 倍率重编码 + 回传接口） |
| 聊天点击世界高亮 | ✅ 已移植（15 秒绿色线框） |
| 检测工具物品 + 合成配方 | ✅ 已移植（含原版材质） |
| 中/英双语 | ✅ 已移植 |
| 自动定时检测（每 20 秒） | ❌ 未移植（面板手动扫描替代） |
| 机器配方库匹配（对 20+ mod 的反射适配） | ❌ 未移植 — 原版针对 1.21 modpack，GTNH 生态不同 |
| 饰品栏（Curios/Accessories）集成 | ❌ 未移植 — 1.7.10 无此类模组 |

## 版权与致谢

- **本仓库的代码为 AI 编写**（由 WorkBuddy / GLM 智能体在人工提出需求与验收反馈下完成），针对
  Minecraft 1.7.10 / Forge 10.13.4.1614 / GTNH 2.8.4 从零重写，**未复制原项目的源代码**。
- **移植自** [3QQQ/PatternChecker](https://github.com/3QQQ/PatternChecker)（NeoForge 1.21.1 AE2 附属）：
  功能设计（样板扫描/校验项、面板、编辑/取出、高亮）以其为蓝本，检测思路参考了原版实现。原仓库
  未对模组代码声明许可证，如需分发请以该仓库的授权为准。
- 构建/运行依赖的开源项目（本仓库不含其代码，运行时由游戏环境提供）：
  - [Applied Energistics 2 Unofficial (GTNH)](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) — 编译期仅依赖其公开 API
  - [NotEnoughItems (GTNH)](https://github.com/GTNewHorizons/NotEnoughItems) — 可选依赖，NEI 集成为反射加载
  - [wildcardpattern](https://github.com/GTNewHorizons/wildcardpattern)、[ae2fc](https://github.com/GTNewHorizons/ae2fc) 等附属模组 — 仅做兼容性探测
  - [GTNH Gradle Example / ModTemplates](https://github.com/GTNewHorizons/ExampleMod1.7.10) — 构建脚本结构参考
- 原模板文件许可：原项目携带 NeoForged MDK 的 MIT 模板许可（`TEMPLATE_LICENSE.txt`），本仓库的
  Gradle 脚本为 GTNH 工具链重写，不包含该模板文件。

# PatternChecker 兼容性检查报告 — GTNH 2.8.4

> **本报告的处置状态**：下列问题已在 **v1.3.0** 中修复 — §4.3（P0，样板 NBT 格式）、§3.2①（P0，非物品条目）、
> §3.2②（P0，根级 NBT 丢失）、§1（P1，NEI 拖拽）、§2.1（P1，NEI 面板遮挡）、§2.2（P2，GUI 高度与标签重叠）、
> §4.3 线程问题（P2，§3.2④ 中的 Netty 线程）、§3.2⑤（P3，存储泄漏/线程安全）、§2.5（P3，客户端线程 + 幽灵槽 tooltip）。
> 后续版本：§9 四个问题见 **v1.4.0**，§10 两个问题见 **v1.4.1**，§11 两个问题见 **v1.5.0**。
> 尚未处理：`scan all` 冷却与权限（§3.2④ 的性能部分）、GUI 容器 windowId 不匹配（§8.1）。
> 各版本修复说明见 `README.md` 对应章节。

- **被检模组**：`C:\Users\s21\Documents\AItemp\PatternChecker`（v1.2.0，源码 + `build/libs/patternchecker-1.2.0.jar`）
- **目标环境**：`C:\Software\Minecraft\Server\GT_New_Horizons_2.8.4_Server`（**只读，未做任何修改**）
  - Minecraft 1.7.10 / Forge 10.13.4.1614 / 226 个 mod jar
- **检查方式**：源码通读（33 个类，3310 行）+ 服务器 jar 反汇编比对（`javap -c`），未启动游戏

---

## 0. 结论摘要

| # | 检查项 | 结论 | 严重度 |
|---|---|---|---|
| 1 | 「JEI 拖拽」 | ⚠️ **GTNH 2.8.4 没有 JEI**。1.7.10 的配方查看器是 **NEI 2.8.44-GTNH**；用户说的"JEI 拖拽"实为 **NEI 的物品面板拖拽（drag-and-drop）**。PatternChecker **未接入 NEI 拖拽接口，拖拽到编辑器幽灵槽无效** | 高 |
| 2 | NEI UI 遮挡 | ❌ 未实现 `hideItemPanelSlot` / `modifyVisiblity`，NEI 物品面板会盖住检查面板右半部分 | 中 |
| 3 | GUI 尺寸 | ⚠️ 编辑器 GUI 高 **244 px**，而 GTNH 常见逻辑画布高度只有 **240 px**（854×480 / 1280×720 自动缩放后都是 240），必然轻微溢出 | 中 |
| 4 | **AE2 版本** | ❌ 服务器是 **rv3-beta-695-GTNH**，模组却按 **rv3-beta-1053-GTNH** 编译，API 与**样板 NBT 格式都不同** | **高（见 §4）** |
| 5 | 「编辑并上传」核心功能 | ❌ 在 695 上写出的样板 NBT 会被 AE2 读成**输入/输出数量 = 0**，等于把样板改废 | **高** |
| 6 | 网络线程 | ⚠️ 所有包处理器直接在世界/方块实体上操作，未调度回服务器主线程 | 中 |
| 7 | 其他模组 | ⚠️ AE2FC / ae2thing / wildcardpattern 等对样板 NBT 有扩展，编辑器重编码会丢字段；NEI 配方传递类（NEE/AE2FC/ae2thing）按 GUI 类注册，**无直接冲突** | 中 |
| 8 | 服务端只读扫描 | ✅ `scan` / `scan all` 只读，不修改世界；✅ 写样板槽会触发 AE2 `onChangeInventory` 通知，逻辑正确 | — |

---

## 1. 「JEI 拖拽」到底是什么

### 1.1 环境事实

服务器 `mods/` 中与配方查看相关的只有：

```
NotEnoughItems-2.8.44-GTNH.jar     ← 唯一的配方查看器
NEIAddons / NEIIntegration / NEICustomDiagram / tcneiadditions / thaumcraftneiplugin
NotEnoughEnergistics-1.7.10-1.7.14.jar
```

**没有任何 JEI jar**。GTNH 2.8.4 是 1.7.10 版本，JEI 从来不存在于 1.7.10。所以"JEI 拖拽"= **NEI 的 drag-and-drop**（GTNH 的 NEI 分支刻意做成 JEI 风格，甚至有 `codechicken/nei/recipe/GuiRecipeTabJEI.class`）。

### 1.2 NEI 拖拽的分发链路（反汇编证据）

```
按住 NEI 物品面板中的一个物品不放，移动鼠标 / 超过 500ms
   └─ codechicken/nei/PanelWidget.mouseDragged(...)        // 置 draggedStack
        └─ PanelWidget.handleDraggedClick(x, y, button)
             └─ PanelWidget.handleGUIContainerClick(stack, x, y, button)
                  └─ for (INEIGuiHandler h : codechicken/nei/api/GuiInfo.guiHandlers)
                         h.handleDragNDrop(guiContainer, mouseX, mouseY, stack, button)
                         ↑ 第一个返回 true 的处理器接管这次拖拽
```

接口签名（取自服务器 jar）：

```java
package codechicken.nei.api;
public interface INEIGuiHandler {
    VisiblityData modifyVisiblity(GuiContainer gui, VisiblityData data);
    Iterable<Integer> getItemSpawnSlots(GuiContainer gui, ItemStack stack);
    List<TaggedInventoryArea> getInventoryAreas(GuiContainer gui);
    boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack draggedStack, int button);
    boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h);
}
// 注册方式（codechicken.nei.api.API）：
public static void registerNEIGuiHandler(INEIGuiHandler handler);
```

NEI 自带的处理器只有 `NEIDummySlotHandler`（只认 `codechicken.lib.inventory.SlotDummy` + `ContainerExtended`）、`CheatItemHandler`、`FillFluidContainerHandler`、`SearchInputDropHandler`、`GuiRecipe`。

### 1.3 PatternChecker 的现状

`GuiPatternEdit`（编辑器）里的 9 输入 + 3 输出槽是**手绘矩形**：

```java
// GuiPatternEdit.java
private int hitSlot(int mouseX, int mouseY) { ... 纯坐标计算 ... }   // 不是 Slot
protected void mouseClicked(int mouseX, int mouseY, int button) {
    int hit = hitSlot(mouseX, mouseY);
    if (hit >= 0) { handleGhostClick(...); return; }   // 命中就 return，不走 super
    super.mouseClicked(mouseX, mouseY, button);
}
```

`ContainerPatternEdit` 只注册了玩家背包的 27+9 个真实 `Slot`，**样板槽一个 `Slot` 都没有**。

**因此：**
- PatternChecker 没有注册任何 `INEIGuiHandler` → 分发链跑到它时无人认领；
- 幽灵槽不是 `Slot` → `NEIDummySlotHandler` 的 `gui.getSlot(mx,my)` 也找不到目标；
- 结论：**从 NEI 物品面板把物品拖到编辑器的输入/输出槽，什么都不会发生**（也不会报错）。

同时失效的还有依赖同一套机制的：NEI 的 `R`/`U` 配方覆盖层拖拽、"?" 配方传递到本 GUI、NEI 删除模式等。

### 1.4 修复方案（照抄 AE2 官方做法）

AE2 自己的样板终端能拖拽，做法非常清楚（`pc-port/ae2-src` 中的参考源码，服务器 jar 里同样是这套）：

```java
// appeng/client/gui/AEBaseGui.java
@Optional.Interface(modid = "NotEnoughItems", iface = "codechicken.nei.api.INEIGuiHandler")
public abstract class AEBaseGui extends GuiContainer implements IGuiTooltipHandler, INEIGuiHandler {

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack draggedStack, int button) {
        this.draggedSlots.clear();
        this.holdingNEIItem = draggedStack;
        return tryClickOrDragSlot(mouseX, mouseY, draggedStack, button);
    }
    private boolean tryClickOrDragSlot(int mouseX, int mouseY, ItemStack is, int btn) {
        Slot slot = this.getSlot(mouseX, mouseY);          // GuiContainer 的槽位查找
        if (slot != null && this.draggedSlots.add(slot)) return handleClickOrDragSlot(slot, is, btn);
        return false;
    }
    protected boolean handleClickOrDragSlot(Slot slot, ItemStack stack, int btn) {
        if (slot instanceof SlotFake fake) { handleClickOrDragFakeSlot(fake, stack, btn); return true; }
        return false;
    }
}
```

外加**一个全局转发器**（AE2 用反射注册，避免 NEI 不存在时崩）：

```java
// appeng/integration/modules/NEIHelpers/NEIGuiHandler.java
public class NEIGuiHandler extends INEIGuiAdapter {
    @Override public boolean handleDragNDrop(GuiContainer gui, int mx, int my, ItemStack dragged, int button) {
        if (gui instanceof INEIGuiHandler h) return h.handleDragNDrop(gui, mx, my, dragged, button);
        return super.handleDragNDrop(gui, mx, my, dragged, button);
    }
    @Override public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) { ... }
}
// 注册：API.registerNEIGuiHandler(new NEIGuiHandler())   （AE2 用 Method 反射调用）
```

**给 PatternChecker 的最小改动**（客户端 only，不需要改服务端，因为编辑器状态仍在 GUI 内、最终仍由 `PacketEditCommit` 提交）：

```java
@Optional.Interface(modid = "NotEnoughItems", iface = "codechicken.nei.api.INEIGuiHandler")
public class GuiPatternEdit extends GuiContainer implements INEIGuiHandler {

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack dragged, int button) {
        if (dragged == null) return false;
        int hit = hitSlot(mouseX, mouseY);
        if (hit < 0) return false;
        // 复用现有逻辑：button==1 视为"加一手"，否则设为该物品
        EditSlot slot = hit < 100 ? inputs[hit] : outputs[hit - 100];
        ItemStack type = dragged.copy(); type.stackSize = 1;
        slot.type = type;
        slot.count = clampCount(Math.max(1, dragged.stackSize));
        return true;                       // 接管，NEI 不再消耗拖拽栈
    }
    @Override public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
        return x + w > guiLeft && x < guiLeft + xSize && y + h > guiTop && y < guiTop + ySize;
    }
    // 其余方法用 INEIGuiAdapter 的默认实现（可继承 INEIGuiAdapter 而不是直接实现接口）
}
```

注册（`ClientProxy.registerRenderers()` 里，注意用 try/catch 或 `Loader.isModLoaded("NotEnoughItems")` 包一层，避免无 NEI 时类加载失败）：

```java
API.registerNEIGuiHandler(new GuiPatternEditDragRelay());
// 或让 GuiPatternEdit 自身注册到一个静态实例（NEI 会把当前打开的 GuiContainer 传进来）
```

> 备选路线：把幽灵槽改成 `codechicken.lib.inventory.SlotDummy` + 让 `ContainerPatternEdit extends ContainerExtended`，这样 NEI 内置的 `NEIDummySlotHandler` 会**自动**支持拖拽。但 `NEIDummySlotHandler` 会调 `NEICPH.sendDummySlotSet(slot, stack)` 把槽位直接同步到**服务端容器**，会绕过 PatternChecker 现有的"服务端重新校验 + 重编码"安全设计，**不推荐**。服务器 jar 中 `CodeChickenCore-1.4.10.jar` 确实仍带这两个类（`SlotDummy`/`ContainerExtended`），技术上可行。

---

## 2. NEI UI 兼容性

### 2.1 面板遮挡

NEI 的物品面板是**浮在 GUI 之上**绘制的，AE2 为此专门实现了 `hideItemPanelSlot`。PatternChecker 没有实现，实测会遮挡：

- `GuiPatternCheckPanel` 宽 248、`GuiPatternEdit` 宽 236，居中显示；
- 以 854×480 窗口（autoscale=2 → 逻辑 427×240）算，检查面板左右边界约 x∈[89,337]，NEI 物品面板默认贴右边缘，宽度通常 140~200 → 会压住右侧几十到上百像素（"扫描全部/清除高亮"、"取出"按钮区域）。

### 2.2 GUI 尺寸 / 分辨率

`GuiPatternEdit` 构造里硬编码 `this.ySize = 244`，但：

| 窗口 | autoscale | 逻辑画布 | 244 高是否溢出 |
|---|---|---|---|
| 854×480（默认窗口） | 2 | 427×240 | ❌ 溢出 4px（`guiTop = -2`） |
| 1280×720 | 3 | 426×240 | ❌ 溢出 4px |
| 1920×1080 | 3 | 640×360 | ✅ 正常 |
| 任何强制 GUI Scale=4 | 4 | — | ❌ 明显溢出 |

`GuiContainer.initGui()` 的 `guiTop=(height-ySize)/2` 允许负值，不会崩，但边框与最下面一行背包槽会被裁掉。建议把编辑器整体压到 **高度 ≤ 230**，或按屏幕高度动态调整。

### 2.3 滚轮

`GuiPatternEdit.handleMouseInput()` 先调用 `super.handleMouseInput()`（NEI 依然收到该事件），随后自行处理悬停槽位的数量增减。因为幽灵槽不在 NEI 的面板区域内，**不会与 NEI 的滚轮翻页冲突**，逻辑没问题。

### 2.4 `mouseClicked` 提前 return

命中幽灵槽时**不调用 `super.mouseClicked`**，等于跳过 NEI 的槽位点击链。由于该坐标下本来就没有真实 `Slot`，无副作用；但也意味着 NEI 的"自定义槽位 GUI"机制无法接管这块区域（这正是 §1 拖拽失效的同一根因）。

### 2.5 其他 UI 细节

- 幽灵槽**没有 tooltip**（AE2 的 `SlotFake` 会走 NEI tooltip 链），悬停看不到物品名。不算 bug，但体验上要补。
- `ClientEditState.apply()`（`PacketEditData` 的处理器）在 **Netty 线程**直接调 `Minecraft.getMinecraft().displayGuiScreen(...)`。1.7.10 里这是线程不安全操作，建议改为 `Minecraft.getMinecraft().addScheduledTask(...)`。

---

## 3. 与其他模组兼容性

### 3.1 无冲突的部分 ✅

| 项 | 说明 |
|---|---|
| 频道名 `patternchecker`、GUI id 0/1、消息 id 0-4 | 均为模组内私有命名空间，全服 226 个 jar 中无重名 |
| 注册的 NEI 配方/覆盖处理器（NotEnoughEnergistics、ae2fc 的 `AE2FC_NEIGuiHandler`、ae2thing 的 `AE2TH_NEIGuiHandler`、ae2_auto_pattern_upload 的 NEI mixin） | 全部按 **GuiContainer 类** 注册（`GuiPatternTerm` / `GuiFluidPatternTerminal` / `GuiPatternInterface` …）。PatternChecker 用的是自己的新类，**不会撞车** |
| `AEApi` 只读 API（`IGrid`、`IInterfaceHost`、`ICraftingGrid`、`IStorageGrid`、`IMEInventory`） | 服务器 695 上全部存在（已逐个 javap 校验） |
| 写入接口样板槽 | `AppEngInternalInventory.setInventorySlotContents` → `IAEAppEngInventory.onChangeInventory` → `DualityInterface.saveChanges()`，AE2 会被正确通知，**不会出现"改了样板但网络还用旧样板"** |

### 3.2 有风险的部分 ⚠️

**① 编辑器只认 ItemStack，非物品条目会被清空**

`PanelActions.fillEntries()` 用 `Platform.loadItemStackFromNBT(tag)` 解析每个 `in`/`out` 条目；`buildList()` 用 `Item.getIdFromItem` / `Item.getItemById` 回写。

服务器上装了这些会往样板里塞**非纯物品**内容的 mod：

- `ae2fc-1.4.120-gtnh.jar`（流体样板，FluidDrop / 流体栈）
- `ae2thing-v1.2.14.jar`（灌注样板终端）
- `postea-1.1.3.jar` + `ae2noultimatepatterns-1.0.1.jar`

对这些样板点「编辑」→ 非物品条目会显示成空槽 → 点「编码并上传」→ **该条目被写成空 NBT，数据丢失**。
建议：`PanelActions.edit()` 里增加"含非物品条目则拒绝编辑"的判断（`Platform.loadItemStackFromNBT` 返回 null 且 `tag` 非空 → 直接拒绝）。

**② `reencode()` 只保留 5 个 NBT 键，会丢第三方扩展字段**

```java
root.setBoolean("crafting", false);
root.setBoolean("substitute", orig.getBoolean("substitute"));
root.setBoolean("beSubstitute", orig.getBoolean("beSubstitute"));
if (orig.hasKey("author")) root.setString("author", orig.getString("author"));
root.setTag("in", ...); root.setTag("out", ...);
```

新版 AE2 给样板加的 `tunnelUuid`（input-only/tunnel 样板必需，见 `ItemTunnelPattern.writeTunnelUuid`）、AE2FC / ae2thing / wildcardpattern 自己的根级字段都不在白名单里 → 重编码后**全部丢失**，其中 `tunnelUuid` 缺失会让样板直接失效。
建议：改为**在原始 tag 的副本上只覆盖 `in`/`out`/`crafting`/`substitute`/`beSubstitute`/`author`**，其余键原样保留。

**③ `wildcardpattern-v1.0.6.jar`**

它的 `WildcardPatternDetails` 依赖自定义 NBT。其样板通常是**合成样板**（`crafting=true`），而编辑器只对 `!isCraftable()`（处理样板）开放，所以基本不会被改到；但若存在处理型通配样板，仍受 ② 影响。

**④ 命令权限等级 0 + `scan all` 的性能**

`/patterncheck` 的 `getRequiredPermissionLevel()` 返回 0、`canCommandSenderUseCommand()` 恒 true，任何玩家都能扫描网络。`scan all` 遍历 `world.loadedTileEntityList` 并对每个方块调 `getGridNode()`，在大基地里是明显的 TPS 抖动源，且**扫描逻辑运行在 Netty 线程**（见 §4.3）。建议：加节流/冷却，或把 `scan all` 提到权限 2。

**⑤ 会话/缓存泄漏**

`PanelStore` / `EditStore` / `HighlightStore` 都是静态 `HashMap<String, ...>`，以玩家名为 key，**玩家退出时从不清理**（`EditStore` 仅在读取时惰性过期）。长期运行的服务器上会缓慢积累。另外这些 `HashMap` 被 Netty 线程与服务器线程并发访问，**不是线程安全的**（`EditStore.cleanup()` 从未被调用）。

### 3.3 客户端 / 服务端

- 模组必须**两端都装**（面板 GUI、编辑、高亮走自定义包）；只装服务端时只有命令聊天路径可用。README 已说明。
- `dependencies = "required-after:appliedenergistics2"` 会阻止玩家在缺 AE2 时进服，正常。
- NEI 的 SMP 拖拽要求服务端有 NEI（`NEIClientConfig.hasSMPCounterPart()`）；GTNH 服务器自带，满足。

---

## 4. AE2 版本差异（本次检查发现的最严重问题）

### 4.1 版本对不上

| 位置 | AE2 版本 |
|---|---|
| 服务器 `mods/appliedenergistics2-rv3-beta-695-GTNH.jar` | **rv3-beta-695-GTNH**（2026-06-19） |
| `build.gradle.kts` 的 `compileOnly` | **rv3-beta-1053-GTNH** |
| Gradle 缓存 | 两个版本都下过（`rv3-beta-695` 与 `rv3-beta-1053` 的 dev jar 都在） |
| `pc-port/ae2-src`（移植参考源码） | 与 1053 同代（含 `getCraftingMultiPatterns`） |

### 4.2 `getCraftingMultiPatterns()` 缺失 —— 已正确兜底 ✅

```java
private static Iterable<ImmutableList<ICraftingPatternDetails>> registeredPatterns(ICraftingGrid crafting) {
    try {
        return crafting.getCraftingMultiPatterns().values();
    } catch (NoSuchMethodError | AbstractMethodError older) {
        return crafting.getCraftingPatterns().values();
    }
}
```

javap 确认：695 的 `ICraftingGrid` **只有** `getCraftingPatterns()`，没有 `getCraftingMultiPatterns()`。编译产物里是 `invokeinterface ICraftingGrid.getCraftingMultiPatterns`，在 695 上抛 `NoSuchMethodError` → 被 catch → 回退成功。**这段是好的**。
（另外验证过：`getCraftingPatterns()` 的 key 是**产物**，但代码只用 `.values()` 里的 `details.getPattern()`，与 key 语义无关，因此"未注册到合成缓存"的判定逻辑是**正确**的。）

### 4.3 样板条目 NBT 格式不兼容 —— 高危 ❌

**695 的实际格式（反汇编 `ContainerPatternTerm.encode()` / `Platform`）**

```java
// 写：appeng/container/implementations/ContainerPatternTerm#createItemTag
tag = new NBTTagCompound();
stack.writeToNBT(tag);                 // 原版 ItemStack NBT：id(short) / Count(byte) / Damage(short) / tag
tag.setInteger("Count", stack.stackSize);   // ★ 关键：Count 被覆盖成 int

// 另：appeng/util/Platform#writeItemStackToNBT 完全同上

// 读：appeng/helpers/PatternHelper 构造器
ItemStack gs = Platform.loadItemStackFromNBT(tag);
// Platform.loadItemStackFromNBT:
//   ItemStack is = ItemStack.loadItemStackFromNBT(tag);
//   if (is != null) is.stackSize = tag.getInteger("Count");   // ★ 只认 int "Count"
```

`PatternHelper` 里**没有** `Cnt` 回退（对整个 class 反汇编搜索 `Cnt` 字符串为空）。

**PatternChecker 的写法**

```java
// PanelActions#buildList
IAEItemStack ae = AEApi.instance().storage().createItemStack(gs);
ae.setStackSize(saturatingMultiply(count, mult));
NBTTagCompound tag = new NBTTagCompound();
ae.writeToNBT(tag);        // ★ AEItemStack.writeToNBT：
                           //    id(short)、Count(byte=0)、Cnt(long=真实数量)、Req、Craft、Damage、tag
result.appendTag(tag);
```

**后果**：695 读到 `Count`(byte) = 0，`getInteger("Count")` 返回 **0**，`PatternHelper` 又没有 `Cnt` 回退 → **每个输入/输出的 `getStackSize()` 都是 0**。而 `convertToCondensedList` 不做数量过滤，`condensedInputs.length` 非 0 → 样板不会被标成 `InvalidPattern`，而是变成**一个"合法但什么都不做"的样板**（静默失效，比报错更难排查）。

> 为什么在开发期没暴露：`rv3-beta-1053` 的读取路径是
> `Platform.readStackNBT(tag, convert)`，它在缺少 `StackType` 键时会 fallback 到
> `AEItemStack.loadItemStackFromNBT(tag)`，而那个方法**读的是 `Cnt`**，并配有注释
> `// migration moment`。也就是说：**这套写法在 1053 上能用，在服务器的 695 上会改废样板。**

**修复（二选一，建议 A）**

```java
// A. 双写，兼容新旧两代
ItemStack gs = new ItemStack(Item.getItemById(s.itemId), 1, s.damage);
if (s.tag != null) gs.setTagCompound(s.tag);
gs.stackSize = (int) Math.min(Integer.MAX_VALUE, saturatingMultiply(count, mult));
NBTTagCompound tag = new NBTTagCompound();
appeng.util.Platform.writeItemStackToNBT(gs, tag);   // 写原版 NBT + int "Count"  → 695 可读
if (s.count > Integer.MAX_VALUE) tag.setLong("Cnt", 真实数量);  // 顺带带上 long Cnt → 新版可读
result.appendTag(tag);

// B. 若只想跑 695：完全照抄 ContainerPatternTerm.createItemTag 的写法
```

### 4.4 「倍率」上限与实际语义

`MAX_MULTIPLIER = 1e9`、`MAX_COUNT = 1e15`（long），但上面 §4.3 说明服务器实际只能用 **int** 承载数量（`Count` 是 int，`getInteger`）。即使修好格式，超过 `Integer.MAX_VALUE` 的数量也会被截断。建议把倍率/数量上限按服务端 AE2 的能力收敛到 int。

---

## 5. 建议修复清单（按优先级）

| 优先级 | 问题 | 位置 | 建议 |
|---|---|---|---|
| **P0** | 样板条目 NBT 用 `AEItemStack.writeToNBT`，695 读成 0 数量 | `gui/PanelActions.java#buildList` | 改用 `Platform.writeItemStackToNBT`（int `Count`），必要时补 `Cnt` |
| **P0** | 编辑器对含非物品条目的样板会清空其条目 | `gui/PanelActions.java#edit/fillEntries` | 检测到解析失败的条目直接拒绝编辑并提示 |
| **P0** | `reencode()` 丢第三方根级 NBT（`tunnelUuid` 等） | `gui/PanelActions.java#reencode` | 在原始 tag 的 copy 上就地改写，不重建 |
| **P1** | NEI 拖拽完全无效 | `client/gui/GuiPatternEdit.java` | `@Optional.Interface` + `implements INEIGuiHandler` + `API.registerNEIGuiHandler` |
| **P1** | NEI 物品面板遮挡 GUI | 同上 | 实现 `hideItemPanelSlot`（AE2 同款写法） |
| **P2** | 编辑器 GUI 高 244 > 240 | `GuiPatternEdit` 构造 | 压到 ≤230，或按屏幕高度自适应 |
| **P2** | 包处理器在 Netty 线程操作世界/方块实体 | 所有 `*Packet*Handler` | `MinecraftServer.getServer().addScheduledTask(...)` 调度回主线程 |
| **P2** | 客户端在 Netty 线程 `displayGuiScreen` | `client/ClientEditState.java` | 同上，`Minecraft.getMinecraft().addScheduledTask(...)` |
| **P3** | `PanelStore`/`EditStore`/`HighlightStore` 泄漏 + 非线程安全 | `check/*Store.java` | 换成 `ConcurrentHashMap`，并在 `PlayerLoggedOutEvent` 里清理 |
| **P3** | `/patterncheck scan all` 无冷却、权限 0 | `command/CommandPatternCheck.java` | 加冷却；`scan all` 提权到 2 |
| **P3** | 幽灵槽无 tooltip | `GuiPatternEdit` | 补 `drawCreativeTabHoveringText` |

---

## 6. 现场验证方法（不用改代码就能确认 P0）

1. 备份存档/网络。
2. 在接口里放一个**处理样板**（例如 1×铁锭 → 1×铁块），跑一次 `/patterncheck scan`，确认能扫到。
3. 用面板「编辑」打开 → 不改任何东西 → 直接点「编码并上传」。
4. 把该样板放进模式终端/接口，用「模式供应器」或接口的推送功能试跑（或直接把样板放进一个 ME 接口，看它是否还会向相邻容器推物品）。
5. 用 `/patterncheck scan` 再扫一次：若出现 `处理样板存在数量异常（≤0）的输入/输出`，即确认 §4.3 的 NBT 数量归零问题。
6. 对照手段：把编辑前/后样板分别放到地上，用 NEI 的 `U` 查看，或用 `CraftTweaker`/NBT 查看命令对比 `in`/`out` 的 `Count`/`Cnt` 字段。

---

## 7. 检查边界说明

- 服务器目录**全程只读**：仅执行了 `ls` / `find` / `cat` 与把 jar 解包到工作区临时目录（`.tmp_tools`，检查完毕后已删除），未写入服务器任何文件。
- 未启动游戏/服务器做运行时验证；所有结论来自源码通读 + 服务器 jar 字节码比对，§4.3 的推断链（写 → 读）已在字节码层面完整闭环，但仍建议按 §6 现场确认一次。

---

## 8. 遗留问题（v1.3.0 之后）

### 8.1 GUI 容器的 windowId 与 `openContainer` 不同步

`PacketPanelAction.EDIT` 走的是"服务端 `openGui` + 自定义 `PacketEditData`"双通道，客户端在
`ClientProxy.getClientGuiElement` / `ClientEditState` 里都是用 `new ContainerPatternEdit(player.inventory)`
**新建**一个容器（`windowId` 默认 0），而不是复用服务端同步过来的那个。因此：

- 编辑器里显示的玩家背包是客户端副本，点它走的是 `windowClick(windowId=0, ...)`，服务端会对不上号 —
  **背包槽点击/Shift 转移不会生效**（只能当"取物品源"用）。
- 面板本身（幽灵槽 + 自定义包提交）不受影响。

要彻底解决，需要让客户端使用服务端下发的那个容器实例（例如在 `getClientGuiElement` 里优先取
`player.openContainer instanceof ContainerPatternEdit` 的实例，并确保 `PacketEditData` 在
`openGui` 之后到达）。

### 8.2 `scan all` 缺少冷却

`/patterncheck scan all` 遍历 `world.loadedTileEntityList` 并对每个方块调 `getGridNode()`；
现已在服务器主线程执行（不再有并发风险），但频繁调用仍会造成 TPS 抖动。建议加冷却，或把它提到权限 2。

### 8.3 排序/多线程细节

`PatternCheckService.scanAll` 用 `IdentityHashMap` 去重网格，行为正确；但扫描结果行数上限 150
（`finishPanel` 里的 `cap`），超大网络的问题会被截断，UI 只显示"其余 N 条已省略" — 属于设计取舍。

---

## 9. 追加的四个问题（v1.4.0 已修）

实测反馈的四个问题，根因与修法如下（均在服务器 jar 里反汇编确认）。

### 9.1 通配样板被误报「空白样板」

`wildcardpattern-v1.0.6.jar` 的实现：

- `com.myname.wildcardpattern.item.ItemWildcardPattern extends appeng.items.misc.ItemEncodedPattern`
  —— 它**是** AE2 样板物品的子类，所以 `item instanceof ICraftingPatternItem` 成立；
- 但样板数据**不在根级 `in`/`out`**：`WildcardPatternState` 把配置写在
  `KEY_INPUT_COMPONENTS` / `KEY_OUTPUT_COMPONENTS`（NBT 列表，列表标签名为
  `WildcardInputComponents` / `WildcardOutputComponents`）里，另有 `KEY_EXPANDED_PATTERN_COUNT`；
  `WildcardPatternGenerator.markAsWildcard()` 会打上根级布尔 `WildcardPattern`；
- `ItemWildcardPattern.getPatternForItem()` 走
  `WildcardPatternGenerator.getDetailsForItem()`，返回 `WildcardPatternDetails`
  （内部包了一层 AE2 的 `PatternHelper`），而不是 `PatternHelper` 本身。

PatternChecker 原来的判定是 `hasData = tag.hasKey("in")`，不成立就直接报
`patternchecker.issue.blankPattern` —— 于是所有通配样板都被当成空白样板。

**修法**：新增"第三方样板"分类。判定顺序为
① 根级 NBT 标记（`WildcardPattern` / `WildcardInputComponents` / `WildcardOutputComponents`）→
② 物品类名族谱匹配 `com.myname.wildcardpattern.item.ItemWildcardPattern`（无需编译期依赖）。
命中后只计数并跳过逐项校验（只有所属模组才知道如何展开），在面板摘要第二行与聊天里单独汇报数量。
同时把"必须有根级 `in`"这个前置条件去掉，改成以 `getPatternForItem()` 的解码结果为准 ——
这样行为良好的第三方样板反而能被正常校验，而真正解不出来的才报空白/无法解析。

### 9.2 「取出」报「样板供应器不存在或未加载」（AE2FC 二合一接口）

AE2FC 的二合一接口有两个形态（`ae2fc-1.4.120-gtnh.jar`）：

- `com.glodblock.github.common.tile.TileFluidInterface extends appeng.tile.misc.TileInterface`
- `com.glodblock.github.common.parts.PartFluidInterface extends appeng.parts.misc.PartInterface implements IDualHost`

**线缆上的部件形态**才是实际用法，而 `PanelActions.slotInventory()` 原来只做
`world.getTileEntity(x,y,z) instanceof IInterfaceHost` —— 部件挂在线缆上时，这个坐标上的
方块实体是**线缆总线**（`TileCableBus`），不是接口，于是直接返回 null → 报"供应器不存在"。
扫描本身没这个问题（扫描是从 `IGridNode.getMachine()` 拿到部件实例的），所以面板能列出来、
按钮也可点，只有取出/编辑会失败。

**修法**：解析顺序改为
① 该坐标的方块实体本身是 `IInterfaceHost`（方块形态接口）→ 直接用；
② 否则当作 `IGridHost`（线缆总线）取其网格，遍历 `grid.getNodes()` 收集坐标匹配的
`IInterfaceHost`；一根线缆上可能挂多个接口，因此用「目标槽位里是否仍是那个样板」消歧
（`ItemStack.areItemStacksEqual`），找不到精确匹配时退回唯一的候选。
取出与编辑（`handleCommit`）共用这个解析，所以一并修好。

`DimensionalCoord`（`appeng.api.util.DimensionalCoord`）有 `getDimension()` 可用于维度校验。

### 9.3 编辑界面改为九宫格 + NEI 合成表覆盖

- **布局**：输入从"9 格一排"改为 **3×3 九宫格**（索引 row-major，与 AE2 `in` 列表顺序一致），
  输出 3 格横排放在九宫格右侧；数量直接画在格子内，悬停显示完整数量 + 名称。
- **NEI 覆盖层**：`API.registerGuiOverlay(GuiPatternEdit.class, "crafting", dx, dy)`。
  偏移量的推导依据是 NEI 自身的注册数据：`RecipeInfo` 里
  `registerGuiOverlay(GuiCrafting, "crafting")` 会委托到 `registerGuiOverlay(class, handler, 5, 11)`
  （默认偏移是 **(5, 11)**，不是 0,0），而原版工作台 3×3 格子位于 (30, 17)
  → 说明配方处理器自身的网格原点是 **(25, 6)**。因此
  `dx = GRID_ORIGIN_X - 25`、`dy = GRID_ORIGIN_Y - 6`。
- **一键填入**：`API.registerGuiOverlayHandler(GuiPatternEdit.class, IOverlayHandler, "crafting")`，
  实现 `overlayRecipe(gui, handler, recipeIndex, shift)`：用
  `handler.getIngredientStacks(recipeIndex)` 的 `PositionedStack.relx/rely` 反算九宫格列行
  （`(relx-25)/18`、`(rely-6)/18`），`getResultStack(recipeIndex)` 作为输出；落在 3×3 之外的一律忽略
  （这样 GT 机器这类异构配方不会填错格子）。已存在同物品的槽位保留原数量。

### 9.4 面板「样板 N 个」与「扫描」按钮重叠

`GuiPatternCheckPanel` 的摘要原先画在 `LIST_TOP - 12 = 34`，而顶部按钮占 y=22..40，直接压字。
**修法**：摘要拆成两行画在按钮下方（y=43 / y=52），列表顶部从 46 下移到 62，
"其余 N 条已省略"固定画在 y=168（原来跟着列表底部浮动，会撞到底部按钮），
底部按钮从 `ySize-24` 下移到 `ySize-22`。可见行数仍是 5 行。

---

## 10. 编辑器渲染与 NEI 拖拽（v1.4.1 已修）

### 10.1 NEI 拖拽为什么不生效

NEI 的"投放"分发挂在 **`GuiContainer.mouseClicked`** 上。`NEITransformer`（NEI 的 ASM 模块）
把 `GuiContainerManager.mouseClicked` 注入到该方法，链路为：

```
GuiContainer.mouseClicked                       ← NEI 注入点
 └─ GuiContainerManager.mouseClicked(x,y,btn)   ← 无条件遍历所有 IContainerInputHandler，无任何门槛
     └─ LayoutManager.mouseClicked(gui,x,y,btn)
         └─ Widget.handleClickExt(x,y,btn)
             └─ PanelWidget.handleClickExt → handleDraggedClick → handleGUIContainerClick
                 └─ GuiInfo.guiHandlers 中每个 INEIGuiHandler.handleDragNDrop(...)
```

（`LayoutManager.onMouseDragged` 只负责"开始拖拽"——`Widget.mouseDragged`，**不分发投放**；
所以投放一定要求那次 `mouseClicked` 能到达 `GuiContainer`。）

编辑器原先的实现是：

```java
protected void mouseClicked(int mouseX, int mouseY, int button) {
    int hit = hitSlot(mouseX, mouseY);
    if (hit >= 0) { handleGhostClick(...); return; }   // ← 命中幽灵槽就不再调 super
    super.mouseClicked(mouseX, mouseY, button);
}
```

幽灵槽正是"要投放的位置"，而这些点击**恰恰被这段提前 return 吃掉**，NEI 永远收不到，于是拖拽
完全没反应。改为**先 `super.mouseClicked`，再处理幽灵槽**；`handleNeiDrag` 成功放置时置一个
`neiDropHandled` 标志，避免随后的光标逻辑（拖拽时光标为空手）把刚放进去的物品又清空。

顺带确认：在幽灵槽坐标上 `GuiContainer.mouseClicked` 内的 `getSlotAtPosition` 返回 null →
`k == -1` → 整段原版槽位逻辑被跳过（且该点在 GUI 矩形内，不会触发 `k == -999` 的"丢出窗外"分支），
所以多调一次 `super` 是安全的。

### 10.2 空白槽位闪烁

幽灵槽原先画在 `drawGuiContainerBackgroundLayer` 里。1.7.10 的 `GuiContainer.drawScreen` 顺序是：

```
drawGuiContainerBackgroundLayer(...)   ← 此时 GL_DEPTH_TEST 仍是开着的，深度缓冲里还留着世界渲染的值
GL11.glDisable(GL_DEPTH_TEST);
super.drawScreen(...);                 ← 按钮
RenderHelper.enableGUIStandardItemLighting();  ... 容器槽位的物品 ...
GL11.glDisable(GL11.GL_LIGHTING);      ← Forge 的修正
drawGuiContainerForegroundLayer(...);  ← 前景层：深度测试已关
```

在背景层画槽位底格 + 物品图标 + 数字，会让它们和面板平面**共面争深度**，并且物品图标被
`renderItemAndEffectIntoGUI` 抬到 `zLevel+50`、数量文字留在 `zLevel+0`，三者深度关系不一致 →
出现闪烁。改法是把幽灵槽整体搬到 `drawGuiContainerForegroundLayer`（深度测试已关，不会再争深度），
并分三遍绘制以免 GL 状态互相污染：

1. 全部底格（平面填充，无光照）
2. 全部图标（临时 `RenderHelper.enableGUIStandardItemLighting()`，图标亮度与背包槽一致）
3. 全部数量数字 + 文本（普通文本绘制）

背景层只保留面板边框与玩家背包格底 —— 纯平面填充，不涉及深度与光照切换。




## 11. 数量按钮与「忽略」功能（v1.5.0 已改）

### 11.1 ÷10 / ÷2 / −1 按钮「无效」

**不是按键失灵，是语义问题。** 这四个（三个减少）按钮原先改的是**延迟生效的全局倍率**
（`GuiPatternEdit.multiplier`），而倍率的下限被 `clampMult()` 夹在 **1**：

```java
private static long clampMult(long v) {
    return Math.max(1L, Math.min(1_000_000_000L, v));   // ← 下限就是默认值
}
```

倍率默认值也是 1，于是 ÷10 / ÷2 / −1 从默认状态按下去得到的是 1 → 1、1 → 1、1 → 1，
**界面上什么都不变**（`×2 / ×10 / +1` 会变，所以看起来像"只有减号坏了"）。

v1.5.0 起六个按钮不再维护倍率，而是**直接、立即改写所有已填槽位的数量**
（`scaleAll(factor, divide)` / `offsetAll(delta)`，下限 1、上限 10¹⁵），效果当场可见；
上传时 `PacketEditCommit.multiplier` 固定为 1，避免二次缩放。单槽微调仍用滚轮。
界面上原来的「倍率 xN」一行改为**输入/输出合计**。

### 11.2 忽略功能

需求是"忽略已审阅的样板，且不再占主面板"。实现要点：

| 决策 | 理由 |
|---|---|
| key 用**样板内容指纹**（`Item.getIdFromItem:damage:FNV64(NBT)`），不用坐标 | "忽略"的语义是"这个样板我知道了"，AE2FC 接口里的副本、为并行复制的多份、搬家换坐标后都应一并生效 |
| NBT 指纹做**规范化**：键名排序遍历 + 跳过 `author` | 1.7.10 的 `NBTTagCompound.tagMap` 是 `HashMap`，直接 `toString()` 的键序依赖表容量/插入史；排序后跨会话稳定，忽略才能真正持久 |
| 忽略表存在 **服务端**，按玩家落在 `config/patternchecker-ignored.txt` | 扫、计数、聊天输出都在服务端；只在客户端过滤会让"错误 N"与列表对不上。文件写在 `config/` 而不是世界存档，跟随实例 |
| 被忽略的样板**仍然建行**（`IssueData.ignored`），只是不计入错误/警告、不输出聊天 | 面板要能把它调出来取消忽略；计数内聚到 `countIssue()` 一处，调用点不再各自 `s.errors++` |
| 重复样板（分组行没有单一物品）用 `DupKey.signature()`（处理标志 + 排序后的输入输出指纹）当 key | 让"重复样板"这类告警同样可忽略 |
| 「显示已忽略」是**纯客户端开关**，包始终下发全部行 | 行索引必须与 `PanelStore` 一一对应，`selected` 因此始终是**全量列表下标**，按钮动作沿用原有按行寻址的协议；切换开关不需要重扫 |
| 忽略/取消忽略后**重跑同一次扫描**（`PanelStore` 记住 `all` 标志） | 总数、列表、行索引三者天然一致，不需要在客户端重算计数 |

副作用（有意为之）：忽略表生效后，`/patterncheck` 聊天路径也会跳过这些样板，并在末尾提示
"跳过 N 条已忽略样板的报告"。

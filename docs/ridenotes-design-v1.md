# Rider Notes —— 设计规格 v2（实现版）

> 状态：**与 `0.47.3` 实现一致**。原 v1 冻结稿中的浮层交互已被"就地编辑 + 摘要块"取代，
> 锚定模型已从"原文搜索"改为"行号（书签语义）"。
> 宿主：JetBrains Rider 2026.2.3.1（`262.10968.170`，`since-build=262`）

---

## 1. 一句话定位

给任意代码区间挂"侧挂笔记"，**一个字节都不写进源文件**。

---

## 2. 数据模型

```
ProjectIndex (.ridenotes/_project.json)
  ├─ groups     : GroupNode[]      分组树（可嵌套）
  ├─ placements : Placement[]      锚点归属 / 开关 / 顺序 / 颜色覆盖
  ├─ tags       : TagDef[]         标签库
  └─ tagGroups  : TagGroupNode[]   标签分组

FileShard (.ridenotes/<escaped>.notes.json)
  └─ anchors : Anchor[]
       └─ notes : Note[]           最新在前
```

### Anchor

| 字段 | 说明 |
|---|---|
| `id` | `a_` + 8 位随机 |
| `startOffset` / `endOffset` | 文档偏移。**会话内**由 RangeMarker 维护；跨会话仅作 `⚠已变更` 的比对基准 |
| `startLine` / `endLine` | 1 基行号。**跨会话定位的唯一依据** |
| `anchorText` | 选中原文（`normalize`：去首尾空白、统一换行） |
| `anchorSha1` | 上者 sha1（保留字段，当前不参与定位） |
| `contextBefore` / `contextAfter` | 各 3 行上下文（保留，供手动重定位打分） |
| `title` | 用户自定义，默认空；显示回落：笔记首行 → `行 N–M` |
| `color` | 调色板索引 `0..7` |
| `createdAt` / `updatedAt` | UTC ISO8601 |

### Note

| 字段 | 说明 |
|---|---|
| `id` | `n_` + 8 位随机 |
| `body` | 纯文本，保留换行 |
| `author` | 当前取系统用户名（待改为 `git config user.name`） |
| `tags` | **结构化字符串数组**（不再从正文解析 `#tag`） |
| `createdAt` / `updatedAt` | UTC ISO8601 |

### GroupNode / Placement / TagDef / TagGroupNode

```kotlin
class GroupNode(id, title, color, visible, parentId, order, locked, compact, tags)
class TagDef(id, name, color, groupId)
class TagGroupNode(id, name, order)
```

**schema 2（0.58.0 起）**：锚点的归属与显示属性存在**锚点自身**：

```kotlin
// Anchor 上新增
var groupId: String? = null      // 所属分组
var orderValue: Double = 0.0     // 组内排序（浮点，拖拽时只重排该组）
var visible / locked / compact   // 自身开关
var colorOverride: Int? = null   // 单条颜色覆盖
```

旧结构里的 `Placement`（存在 `_project.json`）已被**自动迁移并废弃**：
加载 schema 1 时把 placements 合并进锚点、写回分片、清空 placements；
指向已不存在锚点的孤儿记录在迁移中丢弃。

- **生效值**：`visible/locked/compact` = 自身值 OR 祖先组链；`colorOverride` 优先，其次组色，最后锚点自身 `color`。
- **顺序**：同一父级下 **组在前**（按 `GroupNode.order`）、**锚点在后**（按 `orderValue`，再按行号）。

---

## 3. 存储

```
<项目根>/.ridenotes/
  ├── _project.json
  ├── Source__Runtime__Renderer__RenderGraphBuilder.cpp.notes.json
  └── ...
```

- 路径分隔符转义为 `__`；源文件路径相对项目根。
- 写入：临时文件 + 原子替换；`LocalFileSystem.refreshAndFindFileByPath` 刷新 VFS。
- 库根固定为 `<项目根>/.ridenotes`（可配置化未实现）。
- 撤销依赖原始 JSON 快照：`AnchorStore.readRaw / writeRaw / readRawIndex / writeRawIndex`。

---

## 4. 锚定与漂移

**行号锚定，书签语义。**

| 时机 | 策略 |
|---|---|
| 会话内 | `RangeMarker`（即 highlighter 自身）跟随编辑；每次渲染读它的实时区间 |
| 会话内改过内容 | 用新文本刷新 `anchorText`/`anchorSha1`/上下文，跨会话不会被误判 |
| 跨会话打开 | `startLine/endLine` 夹到文档范围，取整行范围 |
| 原文对不上 | 标 `⚠已变更`，**位置不动、不搜索** |
| 文件缺失 / 空文件 | 进面板「无法定位」分组 |
| 手动找回 | 「尝试重新定位」（搜一次 + 确认框）/「重新绑定到当前选区」 |

**为什么不自动搜索**：重复代码会让"唯一命中/上下文打分"选错位置，把笔记挪到别的行；
调参只能在这两类错误间来回摆，维护成本高于收益。搜索降级为显式命令。

`Anchoring.locate()` 只做行号夹取；`Anchoring.textChanged()` 只服务于 `⚠`；
`Anchoring.searchRelocate()` 只被手动命令调用。

---

## 5. 视觉规格

### 5.1 颜色：标记饱和 + 内容低饱和

同一色相拆成两层，避免"要么看不清要么刺眼"：

| 用途 | 取色 | 说明 |
|---|---|---|
| gutter 图标、面板色点、颜色菜单、块左侧 3px 竖条 | `NotePalette.accent(i)` | 不透明饱和色 |
| 代码底色带、摘要块填充 | `NotePalette.fill(i)` | 同色相，alpha = 46 |
| 就地编辑浮层底色 | `opaque(i, editorBg)` | fill 与编辑器背景混合成不透明 |

调色板（8 色循环）：

```
4FC3F7 FFF176 AED581 FFB74D BA68C8 4DB6AC FF8A80 90A4AE
```

### 5.2 编辑器内三件套

1. **底色带**：`RangeHighlighter`，背景色 `fill`，贪婪标记 `setGreedyToLeft(true)` / `setGreedyToRight(false)`。
2. **gutter 图标**：锚点起始行，`accent` 色圆角块，点击直接进入就地编辑。
3. **摘要块**：`addBlockElement` 挂在锚点结束行之后，**一个锚点一个块**。

### 5.3 摘要块结构

```
标题（有才显示）                      ← 普通行
第一条笔记首行            11-09 17:20  ← 时间右对齐贴右边缘（灰 #999）
第一条的续行…                          ← 左对齐，完整多行
第二条笔记首行            11-09 17:25
```

- 宽度**固定**取设置里的「摘要最大宽度」，不随窗口/滚动条变化
- 行数不设上限
- **简洁模式**：标题 + 每条笔记各占一行（都保留，只去掉续行）

### 5.4 就地编辑

摘要块上双击 → 浮层覆盖该块：

- 位置/尺寸/圆角与块一致，底色 `opaque()`
- `Ctrl+Enter` 保存，`Esc` 取消
- 编辑期间该锚点的摘要块被临时移除，避免两层颜色叠加
- 锁定时拒绝并提示

---

## 6. Notes 面板

`JTable`，列：`显示 | 锁定 | 简洁 | 名称`，表头隐藏，行高 32。

- 层级用**左缩进**表达（每级 16px），无原生树
- 锚点行有 2 条以上笔记时，行首显示 `▾/▸`，展开后每条形如 `时间 正文首行` 的子行
- 行尾显示 `#标签`（按标签库颜色）；原文不符时追加 `⚠已变更`
- 拖拽：目标行**上/中/下**三区（进组 / 排前 / 排后），蓝色落点线
- 多选（`Ctrl`/`Shift`）+ 批量删除；锁定项跳过并计数提示
- 需要撤销的操作统一走 `undoable(file, name) { ... }`

---

## 7. 标签

- 标签库是**项目级**数据，树形：分组 → 标签
- 打标签是**勾选菜单**（按分组列子菜单），不手写；`新建标签…` 保留
- 标签只存在 `Note.tags` / `GroupNode.tags`，**不写正文**
- 从标签库删除标签 → 同时从所有笔记与分组上摘除，不留残渣
- 删除标签分组 → 只删分类，标签退回「未分组」

---

## 8. 撤销

快照式：操作前后各存一次 `分片 JSON + 索引 JSON`，`undo/redo` 整体还原并重绘。

覆盖：追加/编辑/删除笔记、删除锚点、删除分组、显示/锁定/简洁、颜色、重命名、标签增删、新建锚点。

未覆盖：拖拽排序、标签库对话框内的改动。

---

## 9. 命令

| 命令 | 快捷键 |
|---|---|
| 新建锚点 | `Ctrl+Alt+N` |
| 删除选中 | `Delete` |
| 重命名 | `F2` |

---

## 10. 设置

入口：Notes 面板工具栏的「设置」按钮（插件自带对话框，未注册为 IDE `Configurable`）。
存储：应用级 `riderNotes.xml`，所有项目共用。

| 项 | 默认 |
|---|---|
| 字体 / 字号 | 跟随 IDE 标签字体，11pt |
| 摘要最大宽度 | 880 |
| 摘要最大行数 | 3（字段保留；非简洁模式已不限行，简洁模式每笔记恒为 1 行） |

---

## 11. 边界与非目标

**支持**：普通文本文件；多光标取主选区；空白选区拒绝建锚点。
**不支持**：Diff 视图内显示；跨文件锚点；折叠区间内可见；写回源码。

**明确不做**：PSI 级函数识别（前端无 C#/C++ PSI）；富文本/图片；AI。

---

## 12. 平台坑（改渲染代码前必读）
1. **`RangeHighlighter` 贪婪标志决定边界行为**
   默认 `false/false`：在起点插入换行会被吸进区间，底色带整体下漂。
   ```kotlin
   highlighter.setGreedyToLeft(true)
   highlighter.setGreedyToRight(false)
   ```

2. **inlay 不跟随文档移动**：每次编辑后必须销毁重建，否则摘要块漂到别的行。

3. **`JTree` / `JTable` 的 `paintComponent` 不会被调用**（走 UI delegate），自绘要重写 `paint`。

4. **`MouseAdapter` 只调 `addMouseListener` 收不到 `MOUSE_DRAGGED`**，必须同时 `addMouseMotionListener`。

5. **行内/块级 inlay 的点击**：`EditorMouseEvent.getInlay()` + `Inlay.getBounds()`，用 bounds 做命中判定。

6. **`UndoableAction` 的 `getAffectedDocuments()`** 返回 `arrayOf(DocumentReferenceManager.create(file))` 且 `isGlobal() = false`，否则编辑器里的 `Ctrl+Z` 不生效。

7. **262 的 `SelectionModel` 没有 `getSelection()`**，用 `selectionStart` / `selectionEnd`。

8. **`JTable`**: `setSelectionMode`、`autoResizeMode`（大写 R）。

9. **CJK 绘制**要用 `UIUtil.getLabelFont()` / `JBTextArea`，自造 `Font` 会 tofu。

10. **构建**：Rider 2026.2 要求 JDK 25 工具链；`local(file("D:/JetBrains Rider 2026.2.3.1"))` 指向本地安装。

11. **尺寸取值的坑（踩了很久）**
    - `editor.contentComponent.width/height` 是**内容**尺寸：一万行的文件高度能到 22 万像素
    - `editor.scrollingModel.visibleArea` 在布局期（`calcWidthInPixels` 被调用时）**恒为 0**
    - 块 inlay 的 `targetRegion` 超出视口时，编辑器不会自动裁剪 → 会画到标签栏和工具窗口上
    - 结论：**宽度**用"取所有来源的最小值"，**绘制**前与 `editor.component.visibleRect` 求交，
      且 `calcWidthInPixels` 要返回不小于视口宽的值，真正的宽度在 `paint` 里决定

12. **增量渲染必须同步刷新 `Anchor` 引用**：`Painted` 长期持有旧实例时，库重载后块会显示旧内容
    （表现为"笔记只显示一行"），切换显示开关才恢复。

13. **`ProjectIndex.placement()` / `group()` 是 O(n) 扫描**：渲染对每个锚点调用 4 次，
    千级锚点就是千万次扫描。必须用 `@Transient` 的 id 索引缓存。

14. **删除锚点要同步删 `placements`**：否则 `_project.json` 无限膨胀（实测 823 KB），
    启动解析和每次索引写入都会被拖慢。

---

## 13. 版本演进（关键决策点）

| 版本 | 变化 |
|---|---|
| 0.1 | 样机：验证前端可加载、highlighter 不被后端冲掉、inlay 可用 |
| 0.10 | 锚点 + 浮层只读列表 |
| 0.23 | 就地编辑浮层取代只读列表；去掉行尾胶囊 |
| 0.24 | 颜色拆两层：标记饱和 + 内容低饱和；块宽固定为设置值 |
| 0.25 | 多条笔记以子节点形式进入面板；文档显示全部笔记 |
| 0.26 | 一条笔记一个块（后被合并）→ 最终：一个锚点一个块，笔记首行右对齐时间 |
| 0.27 | 简洁模式改为"每条压一行"，不再隐藏笔记 |
| 0.28 | 标签结构化（不写正文）；分组可打标签 |
| 0.29 | 标签库分组 + 勾选式打标签 + 拖拽 |
| 0.30 | 过期笔记可见性与重新绑定 |
| 0.31 | 锚定模型改为"偏移 + 文本校验"（模型 B） |
| 0.32 | 最终定为**行号锚定**；`⚠已变更` 只提示不移动 |
| 0.33 | 撤销覆盖面板全部写操作（除拖拽与标签库对话框） |
| 0.36 | 新增本地 HTTP 接口（脚本化建笔记 / 压测）与渲染耗时埋点 |
| 0.37 | 视口裁剪（只画可视区 ±200 行的块）、面板索引缓存、批量接口 |
| 0.39 | 增量渲染（位置/颜色没变的锚点保留 highlighter） |
| 0.42 | 修复绘制越界：绘制前与 `editor.component.visibleRect` 求交 |
| 0.43 | 分配宽度与绘制宽度解耦，配置修改后宽度能立即生效 |
| 0.44 | 修复块引用陈旧（只显示一行）、行内按像素省略、筛选与搜索 |
| 0.47 | O(1) 索引查找、删除时清理 placement、手动「清理归属」 |

---

## 14. 性能设计

| 手段 | 说明 |
|---|---|
| 视口裁剪 | 只为可视区 ±200 行的锚点建块 inlay，其余只保留色带 + gutter |
| 增量渲染 | 位置/颜色未变的锚点保留 highlighter，只有内容签名变化才重建块 |
| 内容签名 | 标题 + 每条笔记 id/时间/长度 + 颜色 + 宽度/字号 + 简洁状态 |
| 索引缓存 | `@Transient` 的 id → Placement/Group 映射，O(1) 查找 |
| 面板脏标记 | 只有写入才重读分片；过滤输入不读盘 |
| 像素省略 | `FontMetrics` + 二分查找，避免每帧逐字测量 |
| 布局变更 | 只重建可见块，用 pending 标记合并连续事件 |

实测（`Perf.cs` 10009 行）：

| 指标 | 1000 锚点 | 2000 | 5000 |
|---|---|---|---|
| 批量导入 | 0.31 ms/条 | — | 0.26 ms/条 |
| 单条追加（HTTP 往返） | 60 ms | 80 ms | 170 ms |
| ├ 写盘 ×2 | 12 ms | 23 ms | 36 ms |
| └ 渲染 | 16 ms | 36 ms | 80 ms |
| 可见块数 | 49 | 49 | 325（密集布局） |

---

## 15. 本地 HTTP 接口

`127.0.0.1:47800`，`postStartupActivity` 启动。所有模型变更通过 `invokeAndWait` 回到 EDT。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ping` | 探活 |
| GET | `/status` | 项目名 / 库路径 / 锚点总数 |
| GET | `/anchors?file=` | 列出锚点 |
| POST | `/anchors` | 单条创建 |
| POST | `/anchors/batch` | 批量创建（一次写盘、一次重绘） |
| POST | `/anchors/delete` | 按 id 删除 |
| POST | `/clear` | 清空文件或整库 |

**未鉴权**，仅绑定本机回环地址。发布前需要 token 或默认关闭开关。

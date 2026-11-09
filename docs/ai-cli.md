# AI / CLI 接口

Sidecar Notes 在 IDE 内启动一个**只绑定本机回环地址**的 HTTP 接口，供脚本或 AI agent 读写笔记。
设计目标：让外部程序能"给某段代码挂笔记"而不需要模拟 UI 操作。

> 当前版本：`0.52.0`。
> **接口默认关闭**，需要在设置里显式启用（普通用户完全无感，也不会占用端口）。
> 启用后除 `/ping` 外所有请求都要带请求头 `X-Sidecar-Token: <token>`。
> 端口可配置，默认 `47800`，被占用时会向后顺延。

---

## 0. 用户如何开启（一次性）

1. 打开 **Sidecar Notes 面板 → 设置**
2. 勾选 **「启用本地 HTTP 接口（供脚本 / AI 使用）」** → 确定
3. 插件会在项目里写一个能力文件：

```jsonc
// <项目根>/.ridenotes/api.json
{
  "port": 47800,
  "token": "a1b2c3d4e5f6...",   // 24 位
  "version": "0.52.0",
  "project": "ConsoleApp1"
}
```

- 同目录会写入 `.gitignore`（内容 `api.json`），**令牌不会被提交**
- 关闭接口时 `api.json` 会被删除

**交给 AI 的说法**：

> 读取项目里 `.ridenotes/api.json`，用其中的 port 和 token 调用 Sidecar Notes 的本地接口，
> 协议见 docs/ai-cli.md。

AI 拿到 port/token 后不需要任何人工复制粘贴。

---

## 1. 发现服务

```powershell
# 从能力文件读端口和令牌（推荐给脚本 / AI）
$api = Get-Content '<项目根>/.ridenotes/api.json' -Raw | ConvertFrom-Json
$base = "http://127.0.0.1:$($api.port)"
$headers = @{ 'X-Sidecar-Token' = $api.token }

Invoke-RestMethod "$base/ping"            # /ping 无需令牌
Invoke-RestMethod "$base/status" -Headers $headers
# {
#   "ok": true,
#   "project": "ConsoleApp1",
#   "basePath": "E:/C#Tools/ConsoleApp1",
#   "libraryPath": ".ridenotes",
#   "version": "0.52.0",
#   "port": 47800,
#   "anchors": 4,
#   "notes": 4
# }
```

`port` 是**实际监听端口**（配置端口被占用时会顺延，最多尝试 10 个）。

调用前先 `ping`：没响应说明 IDE 未运行、插件未安装，或该项目的插件尚未启动完成（启动后约 1–2 秒可用）。

---

## 2. 语义约定

- **锚点（anchor）**：绑定到「文件 + 行范围」的笔记容器，**按行号锚定**（书签语义），不修改源文件。
- **一条锚点可以有多条笔记**（1:N），笔记按时间倒序。
- **行号是 1 基**，与 IDE 里显示的行号一致。
- 所有写入都在 IDE 的 EDT 上执行，接口返回时数据已落盘。
- 颜色由插件按文件自动分配（8 色循环），API 不接受指定颜色。
- **调用方标识**：请求头 `X-Sidecar-Client: my-agent/1.0`（可省略）。
  该值会写入笔记的 `author` 与锚点的 `createdBy`；省略时记为 `api`。
  人工在面板里创建的笔记，作者取自 `git config user.name`（失败回落系统用户名）。

---

## 3. 接口

### 3.1 列出锚点

```
GET /anchors?file=<相对路径>&q=<文本>
```

- `file`：相对项目根的路径（`/` 分隔），可省略表示全部文件
- `q`：在标题 / 正文 / 标签 / 文件路径里做不区分大小写的子串过滤

```json
{
  "ok": true,
  "anchors": [
    {
      "id": "a_1c108278",
      "file": "ConsoleApp1/Perf.cs",
      "startLine": 9,
      "endLine": 9,
      "title": "bench 0",
      "color": 0,
      "notes": 1,
      "tags": ["性能"]
    }
  ]
}
```

### 3.2 新建锚点

```
POST /anchors
{ "file": "ConsoleApp1/Perf.cs", "startLine": 9, "endLine": 9, "title": "…", "body": "…" }
```

- `endLine` 省略时等于 `startLine`
- `body` 为空则不创建笔记（只创建空锚点）
- 行号超出文件范围会被夹到有效区间

```json
{ "ok": true, "id": "a_1c108278", "startLine": 9, "endLine": 9 }
```

### 3.3 批量新建（推荐给脚本 / AI）

```
POST /anchors/batch
{ "file": "…", "startLine": 9, "step": 40, "count": 200, "titlePrefix": "bench" }
```

一次写盘、一次重绘。实测 **0.26–0.31 ms/条**，1000 条约 300 ms。

### 3.4 追加笔记到已有锚点

```
POST /notes
{ "anchorId": "a_1c108278", "body": "补充说明" }
```

```json
{ "ok": true, "noteId": "n_ab12cd34", "file": "ConsoleApp1/Perf.cs" }
```

### 3.5 修改笔记（正文 / 标签）

```
POST /notes/update
{ "anchorId": "a_1c108278", "noteId": "n_ab12cd34", "body": "改过的内容", "tags": ["性能", "GC"] }
```

- `body`、`tags` 都可省略（只传一项就只改那一项）
- `tags` 是**整体替换**，传 `[]` 表示清空标签

### 3.6 删除单条笔记

```
POST /notes/delete   { "anchorId": "a_1c108278", "noteId": "n_ab12cd34" }
```

### 3.7 修改锚点属性

```
POST /anchors/update
{ "id": "a_1c108278", "title": "新标题", "color": 3, "visible": true, "locked": false, "compact": false }
```

所有字段都可选，只传要改的。`color` 是 0–7 的调色板索引（作为该锚点的覆盖色）。

### 3.8 分组

```
GET  /groups                     分组列表 + 每个锚点的归属(placements)
POST /groups                     { "name": "性能优化", "parentId": null }
POST /groups/update              { "id": "g_x", "title"?, "color"?, "visible"?, "locked"?, "compact"? }
POST /groups/delete              { "id": "g_x" }        // 子项上移，不会删笔记
POST /anchors/move               { "id": "a_x", "groupId": "g_x", "order": 0 }
```

`/groups` 返回结构：

```jsonc
{
  "ok": true,
  "groups": [{ "id": "g_x", "title": "性能优化", "color": 2, "visible": true,
               "locked": false, "compact": false, "parentId": null, "order": 0, "tags": [] }],
  "placements": [{ "anchorId": "a_x", "groupId": "g_x", "order": 0,
                   "visible": true, "locked": false, "compact": false, "colorOverride": null }]
}
```

### 3.9 标签库

```
GET  /tags                       标签分组 + 标签
POST /tags                       { "name": "性能", "groupId": "tg_x" }
POST /tags/update                { "id": "t_x", "name"?, "color"?, "groupId"? }
POST /tags/delete                { "id": "t_x" }        // 会从所有笔记上摘掉
```

给笔记打标签用 `POST /notes/update` 的 `tags` 字段（整体替换）。
`/tags` 是**标签库**（颜色、分组），两者独立：笔记上的标签是字符串，库里没有的标签不显示颜色。

### 3.10 混合批处理（推荐给 AI）

```
POST /batch
{ "ops": [
    { "op": "anchor.create", "file": "a.cs", "startLine": 42, "title": "热路径", "body": "…" },
    { "op": "note.add",      "anchorId": "a_x", "body": "补充" },
    { "op": "note.update",   "anchorId": "a_x", "noteId": "n_y", "tags": ["性能"] },
    { "op": "anchor.move",   "id": "a_x", "groupId": "g_1" }
] }
```

- 支持 `anchor.create / anchor.update / anchor.delete / anchor.move / note.add / note.update / note.delete`
- 整批在**批量模式**下执行：分片与索引都只在批末各写一次，每个文件只重绘一次
- 返回 `results` 数组，逐条对应；单条失败不影响其他

实测参考（1000 锚点的文件，100 条 op）：逐条调用约 6 s，`/batch` 约 **100–200 ms**。

### 3.11 增量比对（替代事件订阅）

```
GET /changes?since=<seq>
```

```jsonc
{
  "ok": true,
  "seq": 128,              // 当前序列号，下次带上
  "reset": false,          // true 表示 since 太旧，请全量重拉
  "files": ["a.cs"],       // 这些文件的分片变了 → 重拉 GET /anchors?file=
  "indexChanged": true,    // 分组/标签/归属变了 → 重拉 /groups 与 /tags
  "changes": [{ "seq": 126, "kind": "shard", "file": "a.cs" }]
}
```

典型用法：AI 记住上次的 `seq`，轮询 `/changes`；只有 `files` 里的文件需要重新拉取，
`indexChanged` 为真时才重拉分组和标签。**避免每次全量对比。**

### 3.12 导出

```
GET /export?format=md                 整库 Markdown 报告
GET /export?format=md&file=a.cs       单文件
GET /export?format=pr                 每条锚点一段，带代码摘录，可直接贴进 PR review
```

返回 `text/markdown`（不是 JSON）。示例：

```markdown
## `ConsoleApp1/Perf.cs`

### L42–45 · 热路径
```csharp
public static int M42(int a, int b) { return a * 3 + b - 42; }
```

- **2026-11-09 09:26** 每次调用都会分配，注意 GC 压力。 `#性能`
```

面板里也有：`更多 ▾ → 导出 Markdown 到剪贴板 / 导出 PR 评论到剪贴板`。

### 3.13 删除

```
POST /anchors/delete   { "id": "a_1c108278" }
POST /clear            { "file": "ConsoleApp1/Perf.cs" }   // file 省略 = 清空整库
```

`/clear` 会同时清理索引里的归属记录。

---

## 4. PowerShell 示例

```powershell
$base = 'http://127.0.0.1:47800'
$file = 'ConsoleApp1/Perf.cs'

# 1. 取文件行数、在关键函数上建笔记
Invoke-RestMethod "$base/anchors" -Method Post -ContentType 'application/json' -Body (@{
    file = $file; startLine = 42; endLine = 45
    title = '这里是热路径'
    body  = '每次调用都会分配，注意 GC 压力。'
} | ConvertTo-Json -Compress)

# 2. 按文本搜索笔记
(Invoke-RestMethod "$base/anchors?q=GC").anchors | Format-Table id, file, startLine, title

# 3. 给已有锚点补一条笔记
Invoke-RestMethod "$base/notes" -Method Post -ContentType 'application/json' -Body (@{
    anchorId = 'a_1c108278'; body = '补充：只在大循环里出现。'
} | ConvertTo-Json -Compress)
```

---

## 5. 错误模型

所有接口都返回 JSON，成功 `{"ok": true, ...}`，失败：

```json
{ "ok": false, "error": "file not found: Foo/Bar.cs" }
```

| 场景 | HTTP 状态 |
|---|---|
| 未知路径 | 404 |
| 参数缺失 / 格式错误 | 400 |
| 缺少或错误的 `X-Sidecar-Token` | **401** |
| 内部异常 | 500（`error` 带消息） |

---

## 6. 现状与缺口（给 AI 用还缺什么）

**已够用**：建锚点 / 批量建 / 追加·修改·删除笔记 / 修改锚点属性 / **分组（增删改+移动）** /
**标签库（增删改）** / **混合批处理** / **增量比对** / 按文本或文件查 / 清空 / 带版本的状态查询。

**缺口**：接口侧已覆盖日常所需的全部读写操作。

可选的后续增强（非必须）：

1. 长连接推送（当前用 `/changes` 轮询替代）
2. 锚点跨文件移动（当前只支持改所属分组）
3. 批量操作失败重试的事务语义（现在单条失败不影响其他，但没有回滚）

> 已完成：访问令牌、端口可配置 + 占用顺延、单条笔记编辑/删除、锚点属性修改、
> 分组与标签库读写、混合批处理（含批量写盘）、增量比对、Markdown / PR 导出、调用方审计。

**给 AI 的使用建议**

- 批量写入一律用 `/anchors/batch`，不要循环调 `/anchors`
- 写之前先 `GET /anchors?file=` 查重，避免同一行挂多个锚点
- 行号基于当前文件内容，改代码后行号会漂移（插件本身按行号锚定，不自动追踪文本）
- 生产环境慎用 `/clear`

---

## 7. 相关文件

- 实现：`src/main/kotlin/com/thousandyearsofwar/ridenotes/api/NotesHttpServer.kt`
- 启动：`NotesStartup.kt`（`postStartupActivity`）
- 压测脚本：[../tools/perf-run.ps1](../tools/perf-run.ps1)、[../tools/gen-perf-cs.ps1](../tools/gen-perf-cs.ps1)

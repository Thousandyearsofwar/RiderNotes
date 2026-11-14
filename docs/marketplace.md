# Marketplace 上架资料

用于 JetBrains Marketplace 的「Upload plugin」表单，直接复制粘贴。

## 基本信息

| 字段 | 值 |
|---|---|
| Plugin name | `Sidecar Notes` |
| Version | `0.87.0` |
| Vendor | `thousandyearsofwar` |
| License | MIT |
| Price | Free（可后续改成付费/订阅） |
| Category | Tools |
| Tags | `notes`, `bookmarks`, `code review`, `documentation`, `annotations` |
| Supported products | Rider `2026.2`（`since-build 262`） |
| Source code | https://github.com/Thousandyearsofwar/SidecarNotes |
| Issue tracker | https://github.com/Thousandyearsofwar/SidecarNotes/issues |

## 一句话简介（Summary，限 70 字符内）

```
Attach notes to any code range without touching the source files.
```

## 详细描述（英文）

```markdown
**Sidecar Notes** lets you pin notes to any range of code — without editing the file.

Everything lives in a separate, human-readable JSON library inside your project
(`.ridenotes/`), so it can be committed, reviewed and shared like any other file.

## Why

Comments in code get deleted with the code. TODO trackers lose the line they
referred to. Sidecar Notes keeps the note *next to* the code while the source
file stays clean — no markers, no annotations, no diffs in your PRs.

## Highlights

- Attach 1:N notes to a code range; they follow the code when lines move
- Gutter icon, colour band and an inline summary block under the range
- **Notes panel** with group tree, drag & drop reordering, colours, multi-select
  bulk actions (show / lock / compact / tag / delete / move to group)
- **Tag library** with groups and colours; tags are filtered in the panel
- Clone nodes: show the same note in another place in the list, without a
  second anchor in the editor
- Default group: new notes land there automatically
- Markdown-ish links in note bodies: `[label](anchor:a_1a2b3c)` — click to jump
  between anchors
- Works with any keymap (including the Visual Studio Code keymap)
- Optional **local HTTP API** (off by default): let scripts or AI agents read
  and write notes, with token auth and incremental change polling
- Full-text export to Markdown or PR-review format into the clipboard
- English and Simplified Chinese UI

## Requirements

- Rider 2026.2 or newer
- No network access required; the plugin never sends your data anywhere

## Data & privacy

Notes are stored as JSON in `.ridenotes/` inside your project. The optional
HTTP API binds to `127.0.0.1` only and is off by default.
```

## 详细描述（中文，可选：Marketplace 只有一个描述字段，可英文为主、末尾附中文）

```markdown
给任意代码区间挂「侧挂笔记」，不改源码。笔记存放在项目内的 `.ridenotes/` JSON 库里，
可以随仓库提交、评审、共享。

- 代码区间锚点，行号变化后自动跟随
- gutter 图标 / 色带 / 行下摘要块
- 面板：分组树、拖拽排序、颜色、多选批量操作、筛选搜索
- 标签库：分组与颜色
- 克隆节点：同一笔记在列表里多一个节点，编辑器不重复渲染
- 默认分组：新建笔记自动落进去
- 正文支持 `[文字](anchor:id)` 链接，点击跳转到锚点
- 可选本地 HTTP 接口（默认关闭，仅监听 127.0.0.1），供脚本 / AI 使用
- 导出 Markdown / PR 评论格式
```

## 上架前需要你补的素材

- [ ] **截图**（至少 1 张，建议 3–4 张，1600×1000 左右）
  1. 编辑器里挂着笔记的代码 + 摘要块
  2. Notes 面板（分组 + 标签 + 多选）
  3. 标签库对话框
  4. 设置页（API 开关）
- [ ] **插件图标**：已内置 `META-INF/pluginIcon.svg`（40×40，含深色版），表单里不用再传
- [ ] **Vendor 信息**：公司/个人主页、支持邮箱
- [ ] **隐私政策**：可写「插件不收集任何数据；笔记仅存于项目目录」，Marketplace 表单有该字段

## 上传流程

1. 用 JetBrains Account 登录 https://plugins.jetbrains.com/
2. 右上角头像 → **Upload plugin**
3. 选择 `build/distributions/RiderNotes-0.87.0.zip`
4. 填上表的信息、粘贴描述、上传截图
5. Submit for review（首次审核通常 1–2 个工作日）

## 后续自动化（可选）

Marketplace 提供 Gradle 任务：

```kotlin
// build.gradle.kts
tasks {
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
```

```powershell
$env:PUBLISH_TOKEN = "<Marketplace 个人令牌>"
.\gradlew.bat publishPlugin
```

首次上架仍建议走网页表单（要填的元数据多）。

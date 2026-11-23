# CAP-113 代码库提交文件 Diff 与原文查看

> 能力 ID：CAP-113 ｜ 分类：管理 ｜ 状态：已实现 ｜ 日期：2026-11-23
> 决策记录：落地 CAP-111 开放问题 O1（提交 diff 逐行查看）。diff 与原文**拆两个端点**
> 按需懒加载（patch 与全文体量都可观，合并返回会让默认视图白付一倍流量）；diff 用
> `git show -m --first-parent --format= --patch <sha> -- <path>`（与既有文件清单同一
> `--first-parent` 口径，merge 提交按第一父 diff），增删行数走 `--numstat`（二进制行
> 输出 `-` 天然可判）；原文用 `git show <sha>:<path>`，先 `git cat-file -s` 取体积
> 超限直接截断不拉内容；删除文件的「原文」= 父提交版本（前端传 `parent=true`，
> 后端 `git show <sha>^:<path>`）。前端统一 diff 渲染器自解析 hunk 行号，
> 不引第三方 diff 库。

## 1. 目的

CAP-111 提交日志查看器已支持展开提交看变更文件清单，但点到文件为止——
只能看到「状态 + 路径」，看不到每个文件到底改了什么（R2 连增删行数都没有）。
用户反馈（REQ 代码库优化）：点击变更文件应能看到逐行变更对比（GitHub/GitLab
风格 diff 视图），并支持查看该文件在提交时的完整原文。

## 2. 功能需求

### 文件 Diff 与原文查看（M1）

- **FR-01 单文件 diff 端点**：新增 `GET /api/repos/{id}/commits/{sha}/file-diff?path=`，
  返回 `RepoFileDiffView{path, oldPath?, status, additions, deletions, binary,
  truncated, patch}`。patch 为 `git show -m --first-parent --format= --patch
  <sha> -- <path>` 的 unified diff 原文（含 diff --git/index/---/+++ 头与 @@ hunk）；
  additions/deletions 取自 `git show --numstat`（同 pathspec 过滤），二进制文件
  numstat 输出 `- -` → binary=true 且 patch 可能只有 "Binary files differ" 提示行；
  patch 超 2000 行截断，truncated=true。sha 沿用 `^[0-9a-fA-F]{4,40}$` 白名单；
  path 拒空白/NUL/控制字符，经 `--` 分隔传入不过 shell。
- **FR-02 单文件原文端点**：新增 `GET /api/repos/{id}/commits/{sha}/file-content?path=&parent=`，
  返回 `RepoFileContentView{path, ref, size, binary, truncated, content}`。
  先 `git cat-file -s <ref>:<path>` 取字节数，>512KB 直接 truncated=true 不拉内容；
  否则 `git show <ref>:<path>` 取全文，前 8K 字符含 NUL 判 binary（content 置空）。
  `parent=true` 时 ref 取 `<sha>^`（删除文件看删除前内容）。
- **FR-03 前端文件查看抽屉**：提交详情展开行的文件清单，路径渲染为可点链接，
  点击打开二级抽屉（DRAWER_NESTED 960，叠在提交日志抽屉之上）：标题为文件路径
  （改名带 oldPath → path），头部 Segmented 切换「变更对比 / 查看原文」，默认
  变更对比。变更对比视图像 GitHub：文件头显示路径 + 状态 Tag + 绿色 +adds /
  红色 -dels，正文按 hunk 渲染（hunk 头灰底蓝字、新增行绿底、删除行红底、
  上下文行白底），双列行号（旧/新）。原文视图等宽字体 + 行号展示全文；
  二进制/截断给 Alert 提示。A 状态文件隐藏原文无意义的提示不需要（原文=新增全文，
  正常展示）；D 状态文件原文自动取父提交版本并注明。
- **FR-04 交互细节**：diff/原文各自懒加载（切 tab 才请求），抽屉内切换文件状态
  时缓存当次结果（同一抽屉实例内重复切回不重复请求）；加载失败 showError 并可
  重试。

## 3. 否决方案

- **diff 与原文合并一个端点**：patch 与全文常各上百 KB，默认只看 diff 的场景
  白拉全文；拆开懒加载各取所需。
- **引入 react-diff-view 等第三方库**：项目前端零 diff 依赖，统一 diff 格式规整
  （hunk 头 + 三类行），自解析渲染百余行代码可控，避免为单点视图引依赖与样式体系。
- **后端把 patch 解析成结构化行再返回**：前后端重复维护一套行模型，且丢失
  unified diff 原文的排障价值；传输原文、前端解析更简单。
- **原文走 HTTP Range/流式分段**：内部仓库源码文件体量有限，512KB 截断上限
  已覆盖正常代码文件；超大文件提示截断即可。

## 4. 架构与模块归属

```
devmind-integration（沿用 CAP-111 归属，无新模块）
  GitRepoOpsService    —— 新增 fileDiff / fileContent
  GitRepoOpsController —— GET /{id}/commits/{sha}/file-diff、/{id}/commits/{sha}/file-content
  dto/RepoFileDiffView（新）、dto/RepoFileContentView（新）

frontend features/repos
  api.ts    —— 新增 getRepoFileDiff / getRepoFileContent
  types.ts  —— 新增 RepoFileDiff / RepoFileContent
  components/RepoFileViewDrawer.tsx（新）—— 二级抽屉 + Segmented + diff/原文渲染
  components/RepoCommitsDrawer.tsx —— 文件路径改可点链接，挂文件查看抽屉
```

## 5. 数据模型

无变更（无新表新列）。

## 6. API 概要

- `GET /api/repos/{id}/commits/{sha}/file-diff?path=` → `RepoFileDiffView`
  （新增，全认证用户可读）
- `GET /api/repos/{id}/commits/{sha}/file-content?path=&parent=` → `RepoFileContentView`
  （新增，全认证用户可读）
- 既有 commits/detail 端点不变。

## 7. 安全

- sha 沿用 `^[0-9a-fA-F]{4,40}$` 白名单；diff 路径经 `--` 分隔、content 路径拼在
  `rev:path` 单参数内，GitCli 走 ProcessBuilder 参数数组不过 shell。
- path 拒空白与 NUL/控制字符；git 侧 `rev:path` 只解析仓库树内路径，越界路径
  由 git 报错转 400 可读提示。
- 两个端点均为本地只读 git 操作，GET 全认证用户可读（与 commits/detail 同口径，
  SecurityConfig 仅约束 POST/PUT/DELETE），不触碰凭证。
- patch/原文均有行数/字节截断上限，防超大文件撑爆响应。

## 8. 里程碑

- **M1 文件 Diff 与原文查看**（已交付）：FR-01~04。

## 9. 验收标准

- `mvn -q -pl devmind-integration -am test` 通过（GitRepoOpsServiceTest 新增：
  diff 返回 patch+增删行数、改名带 oldPath、根提交新增文件全 add、二进制 numstat
  判 binary、非法 path/sha 400；原文返回全文与 size、parent=true 取父提交版本、
  超限截断、二进制 content 置空）。
- `npx tsc -b` 通过。
- 起服务后代码库 tab → 提交日志 → 展开提交 → 点击变更文件：抽屉内 diff 视图
  逐行着色 + 行号 + +adds/-dels 统计正确；切「查看原文」显示完整文件；
  删除文件原文展示父提交版本。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 前端自解析 unified diff 不覆盖全部边界（如 \ No newline at end of file 行） | 接受，未知行一律按上下文行渲染不报错 |
| R2 | 原文按 UTF-8 解码，GBK 等非 UTF-8 编码文件可能乱码 | 接受（平台仓库统一 UTF-8，见全局红线）；真撞上再按 charset 探测演进 |
| O1 | 并排（split）diff 视图 | 展望，本期只做 unified 视图 |
| O2 | diff 内代码语法高亮 | 展望，本期纯着色不引入高亮库 |

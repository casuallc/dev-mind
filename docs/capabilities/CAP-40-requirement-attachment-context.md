# CAP-40 需求附件上下文投送

> 能力 ID：CAP-40 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-09-12

## 1. 目的

流程会话（分析/方案/拆分，CAP-37/38）的 taskSpec 只带需求 description 纯文本，其中
内嵌的图片/附件 agent 完全拿不到：

- 本地需求描述是 Markdown，图片为 `![x](/api/attachments/{id}/raw?access_token=...)` 链接——
  claude 不会 fetch URL，链接还需 token，等同死链；
- Jira 来源需求描述是 wiki 原文，图片为 `!name.png!` 标记——附件实体在 Jira 侧，
  仅前端详情页经代理端点（CAP-19 FR-09）渲染，agent 无通道。

本能力把**需求描述引用的附件字节**在上下文装配时打进 ContextPackage，runner 物化到
worktree `.devmind/input/`，agent 用 Read 工具直接读本地文件（claude 原生支持 png/jpg/pdf）。
复用 CAP-34 上下文包通道（服务端装配 → runner HTTP 拉取 → 物化），不引入 open api 下载、
不改 launch 帧协议。

覆盖范围：需求 description 引用的本地附件（CAP-32）+ Jira issue 内嵌图（CAP-19）。
挂 requirementId 的会话（分析/方案/拆分/WI 派发/手工关联）均自动受益。

## 2. 功能需求

- **FR-01 ContextPackage 扩展 inputs**：新增 `List<InputFile> inputs`
  （`InputFile(path, originalName, contentType, base64)`；path = 物化相对路径，白名单
  `[a-zA-Z0-9._-]`，二进制走 base64 同 SkillPackage 模式）。`ContextMaterializer` 物化到
  `<worktree>/.devmind/input/<path>`（防逃逸校验复用 skill 文件同款）。
  **inputs 非空时 schemaVersion 升 2**（空则保持 1，存量行为不变）。
- **FR-02 runner 版本门控（fail-visible）**：`ContextPuller` 拉包后校验
  `schemaVersion > SUPPORTED(2)` 即抛错「上下文包版本过新，请升级 runner」→
  `launched{ok:false}`，不静默丢附件降级启动（红线沿用 CAP-34：拉取/校验/物化失败 =
  launch 失败）。
- **FR-03 需求附件装配 provider**：devmind-flow 新增 `ContextProvider` 实现
  （@Order 40，节序在 knowledge 10/docs 20/skill 30 之后）。`ContextAssemblyRequest`
  增加 `requirementId`（可空，record 末位追加），`SessionContextService.prepare` 从
  会话实体透传。provider 逻辑：requirementId 非空 → 读需求 description → 提取两类引用：
  - `/api/attachments/{32hex}/raw`（Markdown 图片/附件链接）→ 经
    `AttachmentContentResolver` SPI 读字节；SPI 增加 default 方法 `resolveAny`
    （不限图片类型，pdf/text 等一并投送；未装配 attachment 模块 = 无本地附件源）；
  - `!name.png!` / `!name.png|attrs!`（Jira wiki 图片标记，与前端 `JiraDescription`
    同款正则）→ 经新 SPI `IssueAttachmentResolver.resolve(requirementId, filename)`
    （common 定义、devmind-integration 实现，内部复用 `JiraIssueActionService.loadAttachment`
    的 resolve + `fetchIssueAttachment` 带凭据下载链；未装配 integration = 无 Jira 源）。
- **FR-04 降级策略**：单个附件缺失/拉取失败不阻断装配——跳过该文件并在注入块
  标注「不可用：原因」；两类 SPI 均未装配或 description 无引用 → provider 空产出。
  （Jira 是远程 HTTP，抽风不应阻断起会话；本地附件可能已被删除，同理降级。）
- **FR-05 上限保护**：单次投送最多 10 个文件、单文件 ≤ 5MB、合计 ≤ 20MB；超限按
  描述中引用顺序截断，清单节注明「超出上限已省略 N 个」。
- **FR-06 注入块附件节与可追溯**：provider 产出「## 需求附件」节（落 `CLAUDE.local.md`），逐项列出
  物化路径/原始文件名/来源（本地附件 / Jira issue KEY）/说明，并显式指示 agent
  「先用 Read 工具查看这些文件再分析」。ManifestItem 新增 `kind=attachment`
  （ref=attachmentId 或 jira:KEY:name，source=request）落快照，会话详情
  「已注入上下文」可见；前端清单展示补 attachment 类型标签。
- **FR-07 空产出判定纳入 inputs**：`ContextAssembler` 的「无场景背景/无条目节/无 skills/
  无 docs = null」判定增加 inputs 为空条件——挂需求的会话即使无场景无知识命中，
  只要有附件投送也必须产出上下文包（否则附件随 null 包丢失）。
- **FR-08 物化文件命名**：本地附件 `{attachmentId}-{safeOriginalName}`；Jira 附件
  `jira-{issueKey}-{safeName}`（safeName 非白名单字符替换为 `_`）。带 id 前缀防同名冲突。

## 3. 插件化接口

- `ContextProvider`（common，CAP-33 既有 SPI）新增第四个实现：`RequirementAttachmentProvider`
  （devmind-flow）。flow 已依赖 project（RequirementService），两个附件源均以
  `ObjectProvider` 探测注入，不反向依赖实现。
- `AttachmentContentResolver`（common，CAP-32 既有 SPI）新增 default 方法：
  `Optional<ResolvedAttachment> resolveAny(String attachmentId)`（不限 mime；
  default 实现回落 `resolve` 保兼容），devmind-attachment 覆写。
- `IssueAttachmentResolver`（common 新增 SPI）：`Optional<IssueAttachment>
  resolve(String requirementId, String filename)`，`IssueAttachment(filename, contentType,
  bytes, issueKey)`；devmind-integration 注册实现（复用 CAP-19 FR-09 下载链）。
- 无新 REST 端点、无 WS 协议变更（上下文包走既有 HTTP 拉包通道）。

## 4. 依赖关系

- 依赖：CAP-32（本地附件与 resolver SPI）、CAP-19 FR-09（Jira 附件下载链）、
  CAP-33（装配管线/ContextProvider/ManifestItem）、CAP-34（上下文包传输与物化）、
  CAP-37/38（流程会话挂 requirementId 的约定）。
- 被依赖：无新增。

## 5. 数据模型

无新表、无结构变更。`ContextPackage.schemaVersion` 语义扩展（2 = 含 inputs），
不落库（包体只经缓存/HTTP 传输，快照仍只存清单）。

## 6. API 概要

无对外 API 变更。内部变化：

```
ContextPackage:  + List<InputFile> inputs；schemaVersion 2（inputs 非空时）
ContextAssemblyRequest:  + requirementId（末位，可空）
SPI: AttachmentContentResolver.resolveAny(id)
SPI: IssueAttachmentResolver.resolve(requirementId, filename)（新增）
物化: <worktree>/.devmind/input/<path>
```

## 7. 验收标准

- 描述含本地图片引用的需求起分析会话：runner worktree 出现 `.devmind/input/{id}-x.png`，
  `CLAUDE.local.md` 含「## 需求附件」节与路径，agent 会话中 Read 该路径可看到图片；
- Jira 来源需求（描述含 `!name.png!`）起分析会话：Jira 附件被下载物化为
  `.devmind/input/jira-{KEY}-name.png`，清单标注来源 issue KEY；
- 附件已删除/Jira 不可达：会话正常启动，清单节标注「不可用」，不 launch 失败；
- 老 runner（schema 支持 <2）收到含 inputs 的包：launch 失败并报「请升级 runner」，
  不静默丢图；
- 超限（>10 个 / 单文件 >5MB）：按序截断且清单注明省略数量；
- 无附件引用的需求：provider 空产出，装配行为与现状一致（无多余包）；
- 会话详情「已注入上下文」显示 attachment 条目。

## 8. MVP 范围（暂不做）

WI spec / docs 文档正文 / chat 消息里的附件引用投送（本期只认需求 description）；
描述文本改写（把链接替换为本地路径——agent 经清单节自行对应）；Jira 附件内容缓存
（每次装配实时拉取，重建路径会重复拉，量小可接受）；非图片 office 文档（docx/xlsx）
的内容抽取（原样投送字节，agent 能读多少算多少）。

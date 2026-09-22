# CAP-53 claude 工作目录上抬（项目+用户粒度）与代码目录分离

> 能力 ID：CAP-53 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-09-22
> 修订 CAP-51 的「工作树 = claude cwd」设定（FR-01 布局的 cwd 列、FR-12 的 resume 口径）；
> 工作树布局、分支、占用、收口/释放/GC 语义全部不变。
> 构建工作区（CAP-36）、问答沙箱（CAP-30）、worklog 空间（CAP-41）不受影响。

## 1. 目的

CAP-51 把工作树粒度改到「每需求」后，claude 的启动 cwd 跟着落到
`<proj>/<owner>/worktrees/req-<rid>`。claude CLI 的本地状态（transcript、`memory/`、
项目级 CLAUDE.md 解析）**一律以启动 cwd 为归属键**（`<claudeConfigDir>/projects/<slug(cwd)>/`），
于是每个需求变成 claude 眼里一个全新「项目」：

- **memory 跨需求不积累**：同一项目同一用户，需求 A 里沉淀的 memory 在需求 B 里完全不可见，
  需求越多越退化——这是本能力要解决的痛点；
- resume/续接的归属目录随需求数线性增长，FR-12 的保留期治理被迫按最坏情况设。

本能力把 **claude cwd 与代码目录分离**（同构于 CAP-31 多库聚合根：cwd 本来就是聚合视图、
代码在子目录）：

- **cwd 上抬到「项目+用户」粒度** `<proj>/<owner>/`：同项目同用户的所有需求共享一份
  claude 状态目录，memory 跨需求自然积累；
- **代码位置不变**：需求代码仍在 `worktrees/<key>/`（存量会话 `work/`），worktree/分支/
  收口/GC 语义一行不动；
- 开发某个需求时，由平台的路由注入告诉 claude「你的代码在 `<代码目录>/`，进去干活」，
  claude 读写子目录代码与本地工作无异。

## 2. 功能需求

### FR-01 cwd 上抬，代码目录不变

repo 会话（单库/多库、 keyed/存量）的 claude cwd 一律为
`<workspaceRoot>/<projectId>/<owner>/`（`RunnerWorkspace.sessionCwd`，幂等建目录）：

```
<workspaceRoot>/<projectId>/<owner>/                  ← claude cwd（本能力上抬）
<workspaceRoot>/<projectId>/<owner>/main              克隆缓存（不变，agent 禁入）
<workspaceRoot>/<projectId>/<owner>/worktrees/<key>/  需求工作树 = 代码目录（不变）
<workspaceRoot>/<projectId>/<owner>/work/             存量会话代码目录（不变）
```

`sessionDir`（pid 文件落点、`.devmind/output` 扫描目录、finalizer/Reconciler 基准）
**保持指向工作树**，不随 cwd 上抬——对账、产出回传、收口全部不受影响。
chat / worklog / 构建 / 无 repo 块兜底会话的 cwd 不变。

### FR-02 上下文物化拆分：共享落 cwd，会话特定落代码目录

cwd 被同 (项目,用户) 的并发需求会话共享，凡**会话特定**的内容落 cwd 必然互相覆盖
（且 resume 时会读到别的需求的注入），因此物化按归属拆开：

| 内容 | 落点 | 理由 |
|---|---|---|
| `.claude/settings.local.json` 权限白名单 | cwd | 全管线唯一来源且内容为**常量**（KnowledgeContextProvider），共享无冲突 |
| `.claude/skills/<name>/` | cwd | claude 只从 cwd 发现 skills；同项目会话的技能集通常一致，覆盖无害 |
| `CLAUDE.local.md` 注入块（场景背景+知识+当前任务） | **代码目录** | 会话特定；claude 读写代码目录文件时会惰性加载嵌套 CLAUDE.local.md，路由注入再显式引导先读它 |
| `.devmind/docs/`、`.devmind/input/` | **代码目录** | 注入块内以相对路径引用，随注入块同目录解析 |

`ContextMaterializer` 拆为 `materializeShared`（settings+skills）与 `materializeSession`
（注入块+docs+inputs）；原 `materialize` 保留 = 两者全量落同一目录（chat/worklog 沿用）。

### FR-03 路由注入：恒定文件 + 首条消息前缀

cwd 级 `CLAUDE.local.md` 由 runner 写入**内容恒定**的路由文件（每次 launch 幂等覆盖），
不放任何会话特定信息（并发安全）：说明本目录是工作区根而非代码目录、代码目录以首条消息
【代码目录】为准、`main/` 克隆缓存与其他 `worktrees/*` 禁入。

会话特定的代码目录路径由 runner 在**首条用户消息前本地拼接**路由前缀
（`【代码目录】<相对路径>/ ……先读代码目录下的 CLAUDE.local.md`）：

- **只在 runner 本地拼，不回写服务端**：DB 里的 taskSpec 原样保留，`[flow:*]` 首行标记
  分流（RequirementFlowService 读 DB 快照）与 overview 预览均不受影响；
- resume 拉起本就不重放 taskSpec（CliProcessLauncher 既有行为），前缀只影响全新会话。

### FR-04 resume transcript 迁移（一次性）

cwd 变更后，存量会话的 transcript 仍在旧 slug（工作树路径）目录下，`--resume` 在新 cwd
下找不到即续接失败。runner 在带 `resumeSessionId` 的 launch 前做 best-effort 迁移
（`ClaudeStateSupport.migrateTranscripts`）：

- 旧 slug 目录存在且新 slug 目录缺 `<cliSessionId>.jsonl` → 把旧目录**整体复制**（不覆盖
  既有文件）到新 slug 目录——jsonl、同名 sidecar 目录、`memory/` 一并随迁，
  需求粒度时期积累的 memory 也借此并回项目级；
- 复制而非移动（旧目录留给 claude 自身保留期回收）；任何失败只告警，不阻断 launch；
- 多个需求先后迁回同一 cwd 时同名文件先到先得（best-effort 合并，不做内容级归并）。

slug 算法与 claude 实现一致：cwd 绝对路径的非 `[a-zA-Z0-9]` 字符全替换为 `-`
（超长截断情形平台路径不会触及，不实现）。

### FR-05 产出契约不变

`.devmind/output/` 仍相对**代码目录**（taskSpec 里的相对路径表述不变，路由前缀说明
「所有相对路径以代码目录为基准」）；`OutputUploader`/`collect_output` 仍扫 `sessionDir`
（工作树）——扫描路径不动，跨需求并发不会共用一个输出目录。
worklog 的 `.devmind/output/` 在其自有 cwd 下，不受影响。

### FR-06 协议与兼容

- **协议零变更**：无新帧字段；`workspaceKey`/`workspaceOwner`/`projectId` 既有字段足够
  runner 推导 cwd。
- 新 runner + 老服务端：缺 `workspaceKey` = 存量 `work/` 布局，cwd 同样上抬 userRoot
  （代码目录 = `work/`，在其下），行为安全。
- 老 runner + 新服务端：老 runner 不认识本能力（无新字段可忽略），维持 cwd=工作树的
  旧行为，不报错、不错分——功能差异仅是 memory 不共享，属可接受的版本梯度。
- 存量会话 resume：FR-04 迁移兜住；迁移失败的极端情形按 CAP-51 FR-12 的兜底口径
  （claude 自带全 projects 扫描）仍可能续上，续不上报清晰错误。

## 3. 关键设计

- **为什么不反向（代码上抬、cwd 留在工作树）**：cwd 是 claude 状态的归属键，想让 memory
  共享就必须 cwd 共享；代码目录留在需求粒度是 CAP-51 并行开发的根基，不能动。分离是唯一解。
- **共享 cwd 的并发取舍**：同 (项目,用户) 跨需求并行会话共享 cwd 后，并发写冲突点被
  FR-02 的拆分消掉（settings 常量、skills 同内容、会话特定内容全在各自代码目录）；
  claude 自身同 cwd 多进程无冲突（transcript 按 cli session id 分文件）。
- **缓存目录暴露**：cwd 下可见 `main/` 克隆缓存与其他需求工作树。路由文件明确禁入；
  这是提示级约束而非权限级（settings 白名单是全项目常量，不放路径级 deny），
  与平台对 agent 的信任模型一致。
- **memory 以外的收益**：transcript 归属目录数从「需求数」降回「项目×用户数」，
  FR-12 的保留期治理与磁盘对账回到 CAP-42 时代的量级。

## 4. 插件化接口

无新 SPI、无协议变更。runner 内部：`RunnerWorkspace.sessionCwd`（新）、
`ContextMaterializer.materializeShared/materializeSession`（拆分）、
`ClaudeStateSupport.migrateTranscripts`（新）。

## 5. API 概要

无新增端点。

## 6. 验收标准

1. 起需求会话后：claude cwd = `<proj>/<owner>/`；工作树仍在 `worktrees/req-<rid>/` 且
   检出分支、收口/释放/GC 行为与 CAP-51 一致；
2. 物化落点：`.claude/settings.local.json` 与 `.claude/skills/` 在 cwd；
   注入块 `CLAUDE.local.md`、`.devmind/docs/`、`.devmind/input/` 在代码目录；
   cwd 的 `CLAUDE.local.md` 为恒定路由文件；首条消息带【代码目录】前缀；
3. 同项目两个需求并行会话：各自注入互不覆盖（分落各自代码目录），settings/skills 不串；
4. 升级前建的会话 resume：transcript（含 memory/）迁移到新 slug 目录后续接成功；
   重复 resume 幂等（已迁移不复制）；
5. 产出回传零回归：agent 在代码目录写 `.devmind/output/x.md` 后 finish，产出照常落库；
6. chat / worklog / 构建 / 无 repo 兜底会话的 cwd 与物化零变化；
7. DB 中 taskSpec 与 `[flow:*]` 首行标记不被路由前缀污染（前缀只在 runner 本地拼接）。

## 7. 落地状态

- **M1 —— 已完成**：CAP 文档 + runner 改造（cwd 上抬 `RunnerWorkspace.sessionCwd` /
  物化拆分 `ContextMaterializer.materializeShared|materializeSession` / 路由注入
  `CodeDirRouting` / transcript 迁移 `ClaudeStateSupport.migrateTranscripts`）+ Java 单测
  （`ContextMaterializerTest`/`ContextPullerTest` 拆分用例、`ClaudeStateSupportTest`、
  `CodeDirRoutingTest`、`RunnerWorkspaceTest.sessionCwd`）。`tests/cap52_e2e.py` 已补
  CAP-53 断言并实跑通过：cwd 级路由文件存在、settings 落 cwd、代码目录无 settings、
  工作树布局不变（验收 1/2/5/7）；CAP-52 全链路零回归。
  **节点生效需重建 runner jar 并重启 runner 服务**（老 runner 维持 cwd=工作树旧行为，
  协议无变更不报错，见 FR-06）。

# CAP-41 工作日志空间（Worklog as Workspace：runner 持久工作区 + skill/模板驱动生成）

> 能力 ID：CAP-41 ｜ 分类：组装层 ｜ 状态：**M1/M2 已实现（2026-09-14，E2E 通过）；M3 演进未做** ｜ 日期：2026-09-14
> 重构 CAP-28 的报告生成与存储链路；条目管理/git 扫描导入（CAP-28 FR-03/04）保留为素材源不动。

## 1. 目的

CAP-28 的日报/周报是「DB 台账 + one-shot 裸会话文本生成」：素材内联 prompt、claude
无工作目录、输出一段 Markdown 落库即完。实际用不起来：

- 生成物没有"地方"——不能持续积累、不能翻历史文件、格式写死在 prompt 里不可配；
- claude 每次生成都是失忆的（读不到昨天/上周写了什么），周报只能拿到日报文本摘要；
- 与平台主链路（项目→会话→场景→上下文装配→runner 物化）脱节，skill/场景/知识
  资产一个都吃不上。

本能力把工作日志重构为**一个特殊的 WORKLOG 项目 + runner 侧按用户隔离的持久 git
工作区**：

```
管控台用户 ──懒创建──► WORKLOG 项目（projects + kind 列，每用户一个，owner 隔离）
     │ 创建会话（场景=工作日志，绑定 worklog skill + 格式模板）
     ▼ launch 帧 kind:"worklog" + owner 用户名
runner：{user.home}/worklog/<console-username>/   ← 持久目录，本地 git 维护，永不删除
        ├── daily/2026-09-14.md     weekly/2026-W37.md     entries/（素材，预留）
        ├── .claude/skills/worklog/  ← CAP-33 上下文包物化（既有管线零改动）
        └── .devmind/output/         ← 成稿回传契约目录（复用 CAP-37）
     ▼ 会话结束 finalizer（OutputUploader 既有链路）
服务端 upsert daily_reports/weekly_reports（DB 降为镜像：展示/搜索/通知）
```

**事实源 = runner 工作区文件（本地 git 版本化）；DB = 镜像**（前端列表/通知/确认态
仍走 DB，文件丢可从 DB 重建，DB 丢 git 还在）。

## 2. 功能需求

- **FR-01 WORKLOG 项目**：`projects` 增 `kind` 列（NORMAL 默认 / WORKLOG）。首次进
  /worklog 或首次触发生成时按当前用户懒创建（name=「工作日志」、owner=该用户、
  path 存逻辑占位 `worklog://<username>`，跳过 CAP-02 `git rev-parse` 校验）。
  **可见、可进、可用，但危险操作收敛**（2026-09-14 定稿口径）：
  - `/projects` 只读列表正常显示（带「日志」徽标），可「进入」切换为当前项目；
    项目工作区会话/上下文页签照常可用，仓库/构建/部署/测试/发版等代码类页签隐藏；
  - 后台编辑仅放行名称/标签；**path 只读、执行节点锁定**（亲和红线，见下）；
  - **删除/归档禁止**：接口层 409 + 前端不出按钮（防孤儿化 runner 侧日志数据）；
  - 创建会话表单对该 kind 隐藏分支/仓库字段，场景默认选中「工作日志」。**节点亲和**：
  创建时把路由节点固化进 `agent_node_id`（事实源在节点本地，换节点=换事实源），
  之后不可改；该节点离线 → 会话创建 409 明确提示，不静默落到别的节点。
- **FR-02 runner 持久工作区**：launch 帧 `kind:"worklog"`（协议新版本门控，见 §5）。
  `RunnerWorkspace` 增 `prepareWorklog(username)`：
  - 目录 = `{user.home}/worklog/<sanitize(username)>/`（白名单 `[a-zA-Z0-9._-]`，
    normalize 后必须 startsWith 基准目录）；
  - 首次：`git init` + 骨架 commit（`daily/` `weekly/` `entries/` + README
    说明目录契约）；之后**幂等复用**——同一用户的所有 worklog 会话共享同一目录
    （与代码会话"每会话 worktree"根本不同：日志是连续积累的工作区）；
  - **无 worktree、无 push、无删除**：finalizer 只做 `git status` 未提交告警上报；
    目录在 user.home 下不在 workspaceRoot，天然不参与 WorkspaceGc/重启对账扫描；
  - 服务端防写冲突：同一 WORKLOG 项目同时仅允许一个 RUNNING 会话（409）。
- **FR-03 报告生成走真实会话**：定时（cron 沿用 `devmind.worklog.*`）/手动触发 →
  定位本人 WORKLOG 项目 → 创建会话（kind=worklog，挂内置场景）→ prompt = 渲染后
  格式模板 + 当日素材（DB 条目 + git 扫描结果，沿用 FR-04 扫描链）。claude 在工作区
  内可读历史日报/上周文件后写 `daily/yyyy-MM-dd.md`（周报应真的翻上周日报文件），
  按 skill 约定 commit。**worklog 模块停用 one-shot 链路**（`OneShotAgentRunner`
  SPI 保留给后续调用方，worklog 不再消费）。
- **FR-04 skill 与场景种子**：启动时种子（不存在才建，之后用户可编辑）：
  - GLOBAL skill `worklog`：目录契约、文件命名、`git add/commit` 规范、成稿同步到
    `.devmind/output/` 的回传约定、隐私与口吻要求；
  - GLOBAL 场景 `worklog-daily` / `worklog-weekly`：绑定该 skill + prompt 骨架。
  均走现有 skill/场景管理页维护，管控台可见可改。
- **FR-05 格式模板可配置**：`worklog_user_settings` 增 `daily_template_md` /
  `weekly_template_md`（LONGVARCHAR，null=内置默认模板）。占位符：`{{date}}` /
  `{{weekRange}}` / `{{entries}}`（当日条目清单）/ `{{commits}}`（git 扫描清单）。
  渲染在服务端（生成 prompt 时），模板同时物化为 CLAUDE.md 节让 claude 明确输出
  结构要求。设置页两个 Markdown 编辑器（保存即生效，无需重建场景）。
- **FR-06 成稿回传与 DB 镜像**：复用 CAP-37 契约——skill 约定 claude 把成稿写入
  `.devmind/output/daily-<yyyy-MM-dd>.md` / `weekly-<yyyy>-W<ww>.md`，
  `OutputUploader` 随 exit 前 POST `/api/agent/output/{sessionId}`（既有链路，
  单文件 1MB/16 文件上限足够）。服务端消费：按 (user_id, work_date / week_start)
  幂等 upsert `daily_reports`/`weekly_reports`（沿用现有表与 unique 约束，语义降
  为镜像；`session_id` 列记录来源会话）→ 发 `worklog.daily.generated` /
  `worklog.weekly.generated` 事件 → 站内通知（沿用 CAP-06）。回传缺失（claude 没
  写输出目录）→ 生成判失败发降级通知，不落空镜像。
- **FR-07 前端重构**：/worklog 页保留 Segmented[条目|日报|周报]（报告渲染 DB 镜像
  不变，确认/编辑沿用），新增：
  - 「空间」信息条：WORKLOG 项目状态、亲和节点在线态、runner 目录路径（会话事件
    流可见）、「打开会话」入口（直接起 worklog 会话与 claude 对话记日志）；
  - 设置页加日报/周报模板编辑器（FR-05）；
  - 项目列表徽标与工作区页签裁剪（代码类页签隐藏）、会话表单字段裁剪
    （FR-01 可见性口径）；

## 3. 关键设计

- **每用户一个项目**（已定）：owner 隔离使会话历史/场景/知识按人天然分开；
  runner 目录按项目 owner 用户名派生，隔离粒度与项目一致，无第二套映射。
- **文件为事实源**（已定）：git log 即修改历史，claude 可回读；DB 镜像只服务
  展示与通知，允许以文件为准重建（按文件 mtime/内容 upsert，不回写 runner）。
- **条目/git 扫描保留**（已定）：DB 条目与 CAP-29 服务端克隆扫描仍是生成素材
  （注入 prompt），不文件化；条目文件化（`entries/` 目录由 claude 维护）留 M3 演进。
- **回传走既有契约**：不新增 runner→服务端通道，`.devmind/output/` +
  OutputUploader + AgentOutputController 全部复用；worklog 服务端只是
  session_outputs 的一个消费者。
- **节点亲和的代价明示**：亲和节点长期离线 = 该用户日志空间不可用（409 提示），
  不自动漂移节点（漂移即丢历史）。换节点 = 人工决定（新节点重新 init 空空间）。

## 4. 插件化接口

- 无新 SPI。装配走 CAP-33 `ContextAssembler`（场景绑定 skill）；传输/物化走
  CAP-34 内核；回传走 CAP-37/39 产出通道。

## 5. 数据模型与协议

```sql
projects            ── + kind VARCHAR(16) default 'NORMAL'   -- NORMAL / WORKLOG
worklog_user_settings ── + daily_template_md LONGVARCHAR
                         + weekly_template_md LONGVARCHAR
daily_reports / weekly_reports   结构不变（语义：runner 文件镜像；session_id 记来源）
```

WS 协议：launch 帧 `kind` 新增枚举值 `"worklog"` + `worklogOwner` 字段（目录隔离用
用户名，服务端组帧时从项目 owner 取，runner 不信任客户端传值以外的任何身份来源）。
`AgentProtocol` 版本 +1（当前 v4 → v5）；老 runner（不认识该 kind 会落入 legacy 映射
分支跑偏）必须由 `AgentNodeConnector.supports(nodeId, 5)` 门控，不满足 409
「runner 版本过低，请升级」——fail-visible，不静默降级。

## 6. API 概要

```
POST   /api/worklog/workspace/ensure        懒创建本人 WORKLOG 项目（幂等，返回项目+亲和节点状态）
GET    /api/worklog/workspace               本人空间信息（项目/节点在线/最近生成/最近会话）
POST   /api/worklog/daily/generate {date}   改为创建 worklog 会话（异步，202 + sessionId）
POST   /api/worklog/weekly/generate {weekStart}  同上
GET/PUT /api/worklog/settings               + dailyTemplateMd / weeklyTemplateMd
条目/repos/git preview/import、报告 GET/PUT  全部不变（DB 镜像口径）
```

## 7. 验收标准

1. 新用户首次进 /worklog 自动建好 WORKLOG 项目；项目在 /projects 列表可见带「日志」
   徽标，可进入工作区开会话；删除/归档/改 path/换节点被拒绝（409）；
   亲和节点在线时 runner 侧出现 `{user.home}/worklog/<username>/` 且 `git log` 有骨架 commit；
2. 该目录下起第二个并发会话被 409；会话结束目录仍在、不被 GC；
3. 触发日报生成：claude 会话写 `daily/yyyy-MM-dd.md` 并 commit，exit 后 DB 出现同日
   DRAFT 镜像 + 站内通知；重复触发幂等不重复落库；
4. 改模板后再生成，报告结构随模板变化；模板清空回退默认；
5. 周报生成时 claude 回读了上周日报文件（事件流可见 Read 调用），内容体现上周明细；
6. 管控台编辑 worklog skill 后新会话生效（`.claude/skills/worklog/SKILL.md` 内容变化）；
7. 亲和节点离线时生成/会话 409 明确提示，不产生跑偏会话；老 runner（协议 < v5）派发
   worklog 会话 409 提示升级；
8. claude 未写 `.devmind/output/` 时生成判失败并有降级通知，DB 不落空镜像。

## 8. 分期与 MVP 边界

- **M1 骨架**：projects.kind + 懒创建 + 节点亲和 + runner worklog 工作区（init/复用/
  并发锁/GC 豁免）+ 协议 v5 门控 +「打开会话」入口；
- **M2 生成**：skill/场景种子 + 模板设置 + 报告生成改真实会话 + 回传落镜像 + 通知 +
  one-shot 链路从 worklog 摘除 + 前端模板编辑器；
- **M3 演进（暂不做）**：条目文件化（entries/ 由 claude 维护）、runner 目录远端备份
  （push 到 GitLab 私有库）、前端直接浏览/编辑 runner 文件、多节点空间迁移、
  CAP-28 one-shot 相关代码与排错文档清理。

MVP 明确不做：条目文件化、远端 git 同步（本地 git 即事实源，远端备份留 M3）、
服务端读 runner 文件系统的通用通道（镜像只经回传建立）。

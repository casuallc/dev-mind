# CAP-39 会话产出手动推送与按需回传

> 能力 ID：CAP-39 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-09-12

## 1. 目的

CAP-37 打通了「runner 退出时自动回传 `.devmind/output/` → flow 引擎自动落成需求文档」的链路，
但两个缺口仍在：

1. **进行中的会话产出拿不到**：OutputUploader 只在进程退出时跑，分析/设计写到一半想先看看、
   或长会话产出想中途归档，都拿不到文件。
2. **非 flow 会话的产出不沉淀**：普通开发会话/问答里让 agent 写的分析、设计文档只留在
   `session_outputs`（甚至只在 runner 本地），没有入口落成关联需求的正式文档；flow 自动
   登记失败/被跳过时也无法人工补登。

同时做一项 UI 收敛：**裁撤会话详情页**（`/sessions/:id`，与会话工作台信息高度重复、
使用率低），其独有操作（已注入上下文/沉淀经验/清理 worktree）迁入工作台操作条，
全站深链统一到工作台。

## 2. 功能需求

- **FR-01 产出按需回传（协议 v4）**：新增下行帧 `collect_output{sessionId}` 与上行 ack
  `output_collected{sessionId,ok,error}`。runner 收到后异步（虚拟线程，不阻塞 WS listener）
  对**在本节点运行中**的会话执行 OutputUploader 同款扫描上传，完成后回 ack。
  会话不在本节点运行 → `ok:false`（退出时已自动回传，不视为故障）。
  `AgentProtocol.CURRENT` 升 4，服务端按 `supports(nodeId, 4)` 门控，老 runner 返回
  「版本过旧请先升级节点」而非干等超时。
- **FR-02 产出读取/同步端点**：
  - `GET /api/sessions/{id}/outputs` 列出已回传文件（fileName/sizeBytes/updatedAt）；
  - `GET /api/sessions/{id}/outputs/{fileName}` 读内容；
  - `POST /api/sessions/{id}/outputs/collect` 触发 FR-01 收集（历史本机会话/节点离线 409），
    无论收集成败都返回 `{collected, message, files[]}`——前端一次调用拿到最新列表与提示。
- **FR-03 手动推送为需求文档**：`POST /api/sessions/{sessionId}/outputs/publish`，
  body `{fileName, kind, requirementId, mode, docId?, title?, changeNote?}`
  （kind ∈ analysis/design/requirement）：
  - `mode=create`：`DocumentService.create` 落新文档（标题默认「类型名 - 需求标题」）；
    **kind=design 时同步创建 Design(DRAFT) 记录**（否则方案设计 Tab 不可见，对齐 CAP-37
    handleDesignOutput）；会话 workItemId 仅当同属目标需求时透传（DocRequest 一致性校验）。
  - `mode=update`：校验目标文档存在且 requirementId/kind 与请求一致（防跨需求改文档），
    `saveVersion` 存为新版本（changeNote 默认「手动推送自会话 xxx」）。
  - 两模式均登记产物（analysis→TYPE_ANALYSIS、其余→TYPE_DOC，ref=docId，producer=session）。
- **FR-04 工作台推送弹窗**：操作条加「推送产出」按钮，弹窗打开即调 collect 同步（失败
  降级 warning + 展示已存产出），左侧文件列表、右侧 Markdown 预览；每文件「推送为需求文档」
  表单：关联需求（默认会话已关联需求，可改）→ 文档类型 → 新建/更新现有（更新时列出该需求下
  同类型文档供选择）→ 标题/变更说明。成功提示文档 #id 与版本号。
- **FR-05 会话详情页裁撤**：删除 `/sessions/:id` 页面与路由；「已注入上下文/沉淀经验/
  清理 worktree」迁入工作台操作条「更多」下拉；全站 `/sessions/:id` 深链（通知、仪表盘、
  需求关联记录、工作单元、flow 会话链接、AdminProjects）统一改 `/sessions?sid=<id>`，
  工作台按参数自动选中。跨项目深链选中不到的限制与现有需求深链一致，不额外处理。

## 3. 插件化接口

- `AgentNodeConnector` 新增 default 方法 `collectOutput(nodeId, sessionId)`（未装配 agent
  模块抛 CONFLICT），返回新记录 `AgentCollectResult(ok, error)`；ack 只带 ok/error——
  runner 上传先于 ack 同步完成，服务端 ack 后回读 `session_outputs` 无竞态。
- 推送端点放 devmind-flow（唯一同时持有 session/docs/project/artifact 依赖的模块），
  复用 `SessionOutputService.findContent` 与 `DocumentService`/`DesignService`，无新 SPI。

## 4. 依赖关系

- 依赖：CAP-37（产出回传通道与 session_outputs）、CAP-34（WS 协议与版本门控）、
  CAP-03（文档版本化）、CAP-13（Design/产物登记）、CAP-38（需求详情 Tab 深链形态）。
- 被依赖：无新增。

## 5. 数据模型

无新表、无结构变更（`session_outputs` 沿用 CAP-37）。

## 6. API 概要

```
GET  /api/sessions/{id}/outputs                    产出文件列表
GET  /api/sessions/{id}/outputs/{fileName}         产出内容 {fileName, content}
POST /api/sessions/{id}/outputs/collect            触发 runner 即时回传 → {collected, message, files[]}
POST /api/sessions/{id}/outputs/publish            推送为需求文档 → {docId, versionNo, designId?}
     body: {fileName, kind, requirementId, mode, docId?, title?, changeNote?}
WS 下行 collect_output{sessionId} / 上行 output_collected{sessionId,ok,error}（协议 v4）
```

## 7. 验收标准

- 进行中的会话调 collect 后 `.devmind/output/` 文件立即出现在 outputs 列表（不等退出）；
- 老 runner（协议 <v4）collect 返回「版本过旧」可读提示，不超时、不影响已存产出展示；
- create 推送：需求下出现对应 kind 文档（design 同时出现 Design DRAFT 行）；重复 create
  不冲突；update 推送：目标文档版本 +1 且内容更新，跨需求/跨类型 docId 被拒（400）；
- 产物列表出现对应 TYPE_ANALYSIS/TYPE_DOC 行（ref=docId）；
- 工作台可完成「同步 → 预览 → 推送」全流程；「更多」三项功能与详情页原行为一致；
- 原详情页 7 处入口全部落到工作台且自动选中目标会话；`/sessions/:id` 路由不存在。

## 8. MVP 范围（暂不做）

任意 worktree 文件读取（仍限 `.devmind/output/` 约定目录）、已退出会话的再次 collect
（退出时已回传）、推送时的文档内容 diff 对比、跨项目深链自动切换当前项目。

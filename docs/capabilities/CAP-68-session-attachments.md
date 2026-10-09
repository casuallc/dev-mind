# CAP-68 会话附件注入与附件生命周期管理

> 能力 ID：CAP-68 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-10-09

## 1. 目的

CAP-32 建了公共附件底座，但消费方只有问答图片消息与 docs 插图；**项目会话（CAP-05）不能
带图也不能带文件**——ChatPanel 的粘贴/拖拽/选择能力对 `/sessions` 显式门控（后端链路未接）。
同时附件**只增不减**：无标签、无过期、无批量删除，长期堆积只能靠逐个手删。

本能力做三件事：

1. **附件生命周期**：自由文本标签、可空过期时间（到期硬删）、批量删除——附件模块自身增强。
2. **会话注入图片/文件**：图片复用 CAP-32 已全通的 images 帧直读（claude 视觉理解）；
   文件走 input 帧新增 `files` 字段（base64 内联），runner 落盘会话工作区
   `.devmind/incoming/`，发给 claude 的用户消息附路径，agent 用 Read 工具自取——
   **不动 CLI 协议**，图片文件各走最优路。
3. **创建会话即带附件**：新建会话草稿可附文件，随 ContextPackage `inputs` 条目下发物化
   （CAP-40 需求附件先例），agent 起手即见。

## 2. 功能需求

### 附件生命周期（devmind-attachment 自身增强）

- **FR-01 自由文本标签**：`attachments` 加 `tags` 列（逗号分隔自由文本，如「设计稿,临时」）。
  上传时可带；`PUT /{id}/meta` 可改（owner/ADMIN）；列表 `tag=` 参数按单标签精确过滤。
- **FR-02 过期时间**：`attachments` 加 `expires_at` 可空列（null=永久）。上传时可带
  `expireDays`（天数）或显式时间；`PUT /{id}/meta` 可改/清除（置空=恢复永久）。
- **FR-03 定时清理**：模块内 `@Scheduled` 日任务（cron 可配 `devmind.attachment.cleanup-cron`，
  默认每天凌晨低峰），硬删 `expires_at < now` 的附件（删行+删盘，盘文件缺失仅 warn 沿用
  现状）。**不做引用检查**：被历史消息/文档/会话引用的附件到期照样删（引用处裂图/404 由
  用户自担），文档与管理页文案明示。
- **FR-04 批量删除**：`POST /api/attachments/batch-delete {ids:[...]}`，逐项做 owner/ADMIN
  校验，返回每项成败结果（部分失败不整单回滚）。管理页表格行多选 + 「批量删除」按钮，
  配合标签过滤实现「按标签圈选→批删」。

### 会话注入图片/文件（/sessions 链路补齐）

- **FR-05 会话注入图片**：接 CAP-32 已全通的 images 通道——`SessionController.InputRequest`
  加 `images`（与 chats 同构 `[{attachmentId,name}]`）；`SessionManagerService` 以
  `ObjectProvider<AttachmentContentResolver>` 探测解析 base64（**未装配带图即报错，不静默
  丢图**，与 chat 同口径）；前端 ChatPanel 对 `apiBase=/sessions` 放开 `allowImages`。
  runner 侧 `parseImages`/`buildUserMessage` 零改动。
- **FR-06 会话注入文件**：input 帧新增 `files:[{name,mediaType,data}]`（base64 内联，与
  `images` 同构）。runner 收到后落盘**会话工作目录** `.devmind/incoming/<attachmentId>-<净化
  文件名>`（文件名字符白名单与越界拒绝沿用 ContextMaterializer 口径），发给 claude 的用户
  消息文本尾部追加：「用户附加文件已保存到工作区：`- .devmind/incoming/xxx.pdf`（可用 Read
  查看）」。限额：单文件 ≤ 附件模块 `maxSizeMb`（默认 20MB），单条消息 ≤ 5 个；超限服务端
  拒绝（400），不截断不静默。事件流 user 消息 `payload.attachments` 同时带文件引用
  （id/name/contentType），前端渲染下载 chips（CAP-32 FR-06 渲染逻辑同型）。
- **FR-07 创建会话即带附件**：新建会话草稿可附文件/图片（先传附件库得 id），
  `CreateSessionRequest` 加 `attachmentIds`；会话实体加 `attachment_ids` 列持久化。
  ContextAssembler 新增会话附件 ContextProvider（@Order 50，需求附件 40 之后）把附件读成
  `inputs` 条目，物化到 `.devmind/input/attachments/<净化文件名>`（CAP-40 先例：路径白名单、
  越界拒绝、info/exclude 已覆盖 `.devmind/`）；限额对齐 CAP-40（≤10 个/单文件 5MB/合计
  20MB，超限截断并注明）。附件目录提示块随**会话级**上下文注入（不进共享缓存块），告知
  agent 文件位置与用途。

### 前端

- **FR-08 附件管理页增强**：列表加标签列（可编辑）与过期时间列、标签过滤；上传弹窗加
  标签与保留天数字段；行多选 + 批量删除（二次确认，文案明示到期硬删语义）。
- **FR-09 会话工作台**：ChatPanel 加 `allowFiles` prop（仅 `/sessions` 宿主页传入），待发
  文件以 chips 横条展示（非缩略图，可逐个移除）；发送时 `images + files` 同帧。
  SessionsBoard 传 `allowImages allowFiles`；NewSessionDraft 加附件区（上传 + 待带列表 +
  移除）。

## 3. 插件化接口

- 复用 `AttachmentContentResolver` SPI（CAP-32）：session 模块 ObjectProvider 探测注入，
  缺席时带附件 input 报错。
- 新增 `InputFileRef` 载体 record（devmind-common `common.agent` 包）：
  `(attachmentId, name, mediaType, base64Data)`，与 `InputImage` 并列。
- `AbstractSessionRuntime.injectInput` 加带文件重载；`AgentNodeConnector.sendInput` 加
  `files` 重载（旧签名委托空 list，不影响其他消费方）；`CliProcessLauncher.buildUserMessage`
  加带文件重载（服务端与 runner 共用组帧逻辑）。
- 远程通道扩展：input 帧新增 `files` 字段，协议版本号 +1（版本表登记，旧 runner 忽略未知
  字段优雅降级=丢文件，文本仍达）。

## 4. 协议与兼容性

| 组合 | 行为 |
|---|---|
| 新服务端 → 新 runner | images 走 image block 直读；files 落盘 `.devmind/incoming/` + 消息附路径 |
| 新服务端 → 旧 runner | `files` 字段被忽略=文件丢失，文本仍达——**远程用文件需升级 runner**（节点列表展示版本供人工核对，同 CAP-32 图片口径） |
| 附件模块未装配 | 带附件 input 明确报错，纯文本不受影响 |
| 会话图片（images 帧） | 与 chats 完全一致，无新降级面 |

## 5. 数据模型

```
attachments 加列（ddl-auto 平滑加列，存量为 NULL）：
  tags        VARCHAR(512) NULL,   -- 逗号分隔自由文本标签
  expires_at  TIMESTAMP   NULL     -- null=永久；到期由定时任务硬删

sessions 加列：
  attachment_ids VARCHAR(1024) NULL  -- 创建时附带的附件 id，逗号分隔
```

## 6. API 概要

```
PUT    /api/attachments/{id}/meta      改 description/tags/expiresAt（owner/ADMIN；expiresAt 置空=永久）
POST   /api/attachments/batch-delete   {ids:[...]} → 逐项成败结果（owner/ADMIN 逐项校验）
GET    /api/attachments?tag=           列表新增标签过滤（叠加既有 scope/keyword/type）

POST   /api/sessions/{id}/input        body 加 images:[{attachmentId,name}] / files:[{attachmentId,name}]
POST   /api/sessions                   CreateSessionRequest 加 attachmentIds:[...]
```

## 7. 依赖关系

- 依赖：CAP-32（附件底座 + AttachmentContentResolver SPI + images 帧先例）、CAP-30（chat
  图片消息同口径）、CAP-21（远程 input 帧通道）、CAP-34（runner 零执行调度）、CAP-40
  （ContextPackage inputs 物化先例）、CAP-05（项目会话）。
- 被依赖：后续任何「给 agent 递材料」的场景（知识库文件、评审附件等）复用同一通道。

## 8. 验收标准

- 上传附件带标签与保留天数，`PUT /meta` 可改；列表按标签过滤正确；
- 过期附件被定时任务硬删（行+盘无残留），未过期与永久附件不受影响（E2E 用短过期+短 cron
  触发验证）；
- 批量删除权限矩阵正确（owner/ADMIN 逐项），部分失败返回逐项结果；
- 会话中粘贴图片发送：事件流渲染缩略图，claude 收到 image block（回答体现看图内容）——
  远程 runner 会话同样生效；
- 会话中附加文件发送：runner 工作区 `.devmind/incoming/` 出现净化名文件，agent 用 Read
  读到内容并在回答中体现；事件流渲染文件 chips 可下载；
- 创建会话即带附件：agent 起手即知 `.devmind/input/attachments/` 文件并正确使用；
- 旧 runner 收到带 `files` 的 input 帧：文件丢失但文本正常送达（降级不崩）；
- 文件名含路径穿越字符（`../` 等）被拒绝；超限额（大小/个数）服务端 400。

## 9. 暂不做

引用计数/引用保护（到期硬删，裂图自担）、软删除/回收站、分片上传/断点续传、大文件走
HTTP 中转（CAP-65 有先例，20MB 内 WS 内联足够）、chats 链路文件支持（问答保持纯图片，
文件需求在会话场景）、标签自动补全/建议、附件用量配额。

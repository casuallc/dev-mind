# CAP-32 公共附件管理（Attachment）

> 能力 ID：CAP-32 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-07

## 1. 目的

平台此前没有任何内容型文件/图片上传能力（仅有的 multipart 端点都是包中转：runner jar、
skill zip、服务器文件上传），chat 输入框与 docs 编辑器都是纯文本，图片只能靠外链。

本能力建立**独立的公共附件管理模块 `devmind-attachment`**：任何二进制上传即附件，
统一存储、统一元数据、**每个附件生成唯一字符串 id（attachmentId），其他能力只引用 id**。
图片类附件支持内联渲染（图床用法），非图片附件仅提供下载。首批消费方：CAP-30 问答
（图片消息真正下发 claude 视觉理解）、CAP-03 文档编辑器（粘贴/拖拽插图）；后续知识库、
通知等能力可同样经 id 引用接入。

**共享不复制**：附件内容解析经 `devmind-common` 的 `AttachmentContentResolver` SPI 暴露，
消费方（chat runtime）ObjectProvider 探测注入，不反向依赖实现模块。

## 2. 功能需求

- **FR-01 统一附件模型**：任何文件上传即附件。元数据落 `attachments` 表，二进制落本地磁盘
  `data/attachments/<yyyy>/<MM>/<attachmentId>.<ext>`（先写 `.tmp` 再原子 move）。
- **FR-02 唯一字符串 id**：`attachmentId` = SecureRandom 16 字节 hex（32 字符），对外 URL、
  事件引用、markdown 插图一律用它，不暴露内部自增主键与磁盘路径。
- **FR-03 归属与可见性**：附件带 `scope` 字段，默认 `PRIVATE`（仅上传者+ADMIN），可切换
  `SHARED`（全体登录用户可读）。列表 = 本人全部 + 他人 SHARED；ADMIN 见全部。
  切换/删除仅上传者或 ADMIN。
- **FR-04 原始访问**：`GET /api/attachments/{id}/raw`——图片类（content_type 前缀 `image/`）
  返回 `Content-Disposition: inline` + 原样 Content-Type（图床直链）；非图片返回
  `attachment; filename=` 触发下载。鉴权支持 `Authorization: Bearer` 与
  GET `?access_token=` 两种方式（img 标签无法带 header）。
- **FR-05 分页列表**：`scope`/`keyword`（文件名模糊）/`type=image|other` 过滤，时间倒序。
- **FR-06 chat 图片消息**：问答输入框支持粘贴/拖拽/选择图片，随消息发送；事件流 user 消息
  payload 携带附件引用（`attachments:[{attachmentId,name,contentType}]`，只放引用不放
  base64），前端按类型渲染（图片缩略图可放大，非图片下载链接）。
- **FR-07 下发 claude**：图片附件经 stream-json image content block（base64）随 user
  message 写入 claude stdin，image blocks 在前 text 在后。远程节点会话经 input 帧内嵌
  base64 送达 runner（WS 帧无大小上限；旧 runner 忽略未知 `images` 字段优雅降级=丢图，
  **远程用图要求 runner 升级到本期版本**，节点列表已展示版本供人工核对）。
- **FR-08 文档插图**：docs Markdown 编辑器粘贴/拖拽图片 → 自动上传 → 光标处插入
  `![](<raw url>)`；上传中先插占位符完成后替换。
- **FR-09 附件管理页**：后台管理「内容」分组新增「附件管理」（`/admin/attachments`，旧
  `/attachments` 重定向）：上传（弹窗内选文件 + 描述信息 + 逐文件进度条）、列表
  （缩略图/文件名/描述/类型/大小/scope/上传者/时间）、复制 id / 复制 URL、预览或下载、
  切 scope、删除。

## 3. 插件化接口

- `AttachmentContentResolver`（devmind-common `common.attachment` 包）：附件内容解析 SPI，
  `Optional<ResolvedAttachment> resolve(String attachmentId)`（含 contentType + bytes）。
  实现方 devmind-attachment 注册 Bean；消费方（CAP-30 chat runtime）ObjectProvider 探测
  注入，未装配时带附件的 input 直接报错（**不静默丢图**）。
- `InputImage`（devmind-common `common.agent` 包）：运行时图片附件载体 record
  `(attachmentId, name, mediaType, base64Data)`，`AbstractSessionRuntime.injectInput` 新增
  带图重载，`CliProcessLauncher.buildUserMessage` 新增带图重载（服务端与 runner 共用）。
- 远程通道扩展：input 帧新增 `images:[{mediaType,data}]` 字段（`AgentNodeConnector.sendInput`
  重载，旧签名委托空 list，不影响其他消费方）。

## 4. 协议与兼容性

| 组合 | 行为 |
|---|---|
| 新服务端 → 新 runner（本地/远程） | input 带图 → image block 下发 claude |
| 新服务端 → 旧 runner | runner 忽略 `images` 字段，图片丢失，文本仍达——**远程用图需升级 runner** |
| 附件未装配（devmind-attachment 不在 classpath） | 带附件 input 报错提示，纯文本不受影响 |

## 5. 数据模型

```
attachments(id 自增 PK,
            attachment_id VARCHAR(32) UNIQUE,   -- 对外唯一字符串 id
            original_name VARCHAR(255),
            content_type VARCHAR(128),
            size_bytes BIGINT,
            sha256 CHAR(64),
            scope VARCHAR(16) DEFAULT 'PRIVATE',  -- PRIVATE | SHARED
            storage_path VARCHAR(512),            -- 相对 rootDir
            description VARCHAR(512),             -- 上传时可选描述（关键字搜索覆盖）
            uploaded_by VARCHAR(64),
            created_at TIMESTAMP)
```

chat 侧**零表结构变更**：附件引用存 `chat_events.payload`（既有 16MB CLOB JSON）。

## 6. API 概要

```
POST   /api/attachments                  上传（multipart file + 可选 scope/description）→ AttachmentView
GET    /api/attachments?scope=&keyword=&type=&page=&size=   分页列表
GET    /api/attachments/{id}             元数据
GET    /api/attachments/{id}/raw         原始字节（图片 inline / 非图片下载；header 或 ?access_token=）
PUT    /api/attachments/{id}/scope       切 scope（owner/ADMIN）
DELETE /api/attachments/{id}             删除（owner/ADMIN，删行+删盘）
```

## 7. 前端

- `shared/attachments/`：上传/URL 拼装/类型判定公共 helper（`uploadAttachment`、
  `attachmentRawUrl(id)` 拼 `?access_token=`、`isImageAttachment`），供 chat/docs/管理页共用。
- `shared/chat/`：ChatPanel 加图片输入（粘贴/拖拽/选择，`allowImages` prop 显式门控——
  仅问答传入，项目会话后端链路未接不开放）；ChatStream user 消息按附件类型渲染。
- `features/attachments/`：附件管理页（布局遵循前端内容区布局约定）。
- `features/docs/`：编辑器粘贴/拖拽插图。
- 菜单：后台「内容」分组加「附件管理」`/admin/attachments`（仅 ADMIN），旧 `/attachments` 重定向。

## 8. 依赖关系

- 依赖：CAP-01（鉴权/JWT 过滤器扩展/uploadedBy）、CAP-30（chat 图片消息消费方）、
  CAP-21（远程 input 帧通道）、CAP-03（文档编辑器消费方）。
- 被依赖：任何需要文件/图片引用的后续能力（知识库附件、通知图片、报告产物等）。

## 9. 验收标准

- 上传图片得到 attachmentId，`/raw` 两种鉴权方式均可访问，图片 inline 渲染、非图片触发下载；
- 列表可见性矩阵正确（owner/他人/ADMIN × PRIVATE/SHARED），切 scope 即时生效，删除后盘文件无残留；
- 问答粘贴图片发送：事件流渲染缩略图，claude 实际收到 image block（回答体现看图内容）；
  远程节点会话同样生效（runner 为本期版本）；
- docs 编辑器粘贴图片自动插入 markdown 图片语法，预览正常显示；
- 「附件管理」页完成上传/预览/下载/复制/删除全流程；
- 附件模块缺失时纯文本问答不受影响，带图 input 明确报错。

## 10. 暂不做

短期签名 URL（当前 query token 会出现在 URL/访问日志，本地优先场景接受）、runner 反向
回拉取图（省带宽但引入 runner→server HTTP 凭据语义，当前 input 帧内嵌 base64）、软删除/
回收站、S3/MinIO 对象存储后端、创建会话首条消息内嵌图（前端创建后走标准 input 发图）、
图片缩略图生成/压缩。

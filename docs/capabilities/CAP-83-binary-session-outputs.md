# CAP-83 会话产出二进制文件回传（xlsx/图片等，走附件管理）

> 能力 ID：CAP-83 ｜ 分类：Agent 域 ｜ 状态：设计定稿（v2 修订）｜ 日期：2026-10-22
> 修订 [CAP-37](CAP-37-flow-output-pipeline.md)（产出回传）、[CAP-39](CAP-39-session-output-publish.md)
> （产出读取/推送文档）；复用 [CAP-32](CAP-32-attachment.md) 附件管理。
> 决策记录：
> 1. **二进制产出一律进附件管理（CAP-32），走独立上传通道，不进 session_outputs 文本
>    通道**（v2 用户决策，否决 v1 的 base64-over-JSON + 字节列方案）——xlsx 等交付物
>    本质是附件：附件管理页可见可管（scope/tags/过期），下载/图片预览复用既有
>    `/api/attachments/{id}/raw`（支持 `?access_token=` 直链），DB 不塞二进制。
> 2. **文本/二进制判定 = 严格 UTF-8 解码**，不用扩展名白名单——与存量行为兼容（能解出
>    UTF-8 的仍走文本 JSON 通道，flow 引擎文本消费零改动）；扩展名清单必然漏判
>    （GBK csv、无后缀文本等），解码结果是唯一可靠判据。
> 3. **上传通道 = 裸字节 POST**（body 即文件字节，文件名/contentType 走 query），不拼
>    multipart——runner 是无 Spring 瘦 jar，手拼 multipart 边界体无收益；服务端
>    AttachmentService 加一个 InputStream 存储入口复用既有落盘逻辑。
> 4. **附件归属 = 会话 createdBy（回退 workspaceOwner）+ scope=PRIVATE**——附件管理页
>    出现在会话发起者名下，仅本人+ADMIN 可见；产出可能含未公开交付物，默认最小可见面。
>    tags 打 `session-output` 标识来源，description 记 sessionId+fileName 供追溯。
> 5. **同名覆盖删旧附件**：同 sessionId+fileName 重复回传（重跑/重新分析）指向新附件、
>    旧附件硬删——与文本产出「同名只留最新」语义对齐，不留孤儿文件。
> 6. **大小上限沿用附件配置**（devmind.attachment.maxSizeMb，默认 20MB）——runner 侧
>    本地闸对齐 20MB 超限跳过；服务端以配置为准 400 兜底。产出独立的 1MB/4MB 总量闸
>    只约束文本通道。
> 7. **session_outputs 加 `attachment_id` 引用列（不加字节列）**：附件行 content 恒
>    null；列表项带 attachmentId，前端下载/图片预览直走 attachments raw 端点，
>    服务端不新开下载端点。
> 8. **部署顺序仍先服务端后 runner**：新 runner + 老服务端 → attachment 端点 404，
>    runner 记 warn 跳过该文件（红线：上传失败不阻塞 exit 帧），不丢其他产出。

## 1. 目的

CAP-37 产出回传全链路按 UTF-8 文本设计：runner `Files.readString` 读文件，二进制文件
（xlsx 等）解码抛 `MalformedInputException` 被当「读取失败」静默跳过（仅 runner 本地
warn 日志），服务端 `content` 列也是 CLOB。agent 越来越常产出 xlsx/docx/图片等二进制
交付物（如监控指标清单），这些文件必须能随会话产出回传、在产出弹窗可见、可下载、
纳入附件管理体系。

## 2. 功能需求

### M1 二进制回传全链路（一次交付）

- **FR-01 runner 两路回传**：`OutputUploader` 改读字节流，严格 UTF-8 解码
  （malformed/unmappable 均 REPORT）：成功 → 按原文本项走既有 JSON 通道（行为与现状
  一致，1MB/16 文件/4MB 总量闸不变）；失败 → 独立通道上传：`POST
  /api/agent/output/{sessionId}/attachment?name=<fileName>&contentType=<mime>&token=`，
  body = 原始字节。contentType 取 `Files.probeContentType`，null 兜底
  `application/octet-stream`。本地闸：单文件 >20MB 跳过（warn）；上传失败只记错误
  不抛出（红线：exit 帧必须照常上行）。collect_output 即时回传路径同享。
- **FR-02 附件存储 SPI**：common 新增 `AttachmentStore`（与 `AttachmentContentResolver`
  同包同模式）：`store(InputStream, size, originalName, contentType, scope, uploadedBy,
  tags, description) → attachmentId` + `delete(attachmentId)`（系统内部删，bypass 用户
  校验，供覆盖删旧）。attachment 模块实现：AttachmentService 抽 InputStream 落盘私有
  方法（tmp + 原子 move + sha256 沿用），现有 MultipartFile upload 改调它（零行为变化）。
- **FR-03 服务端接收与编排**：agent 模块新端点（与 AgentOutputController 同 token
  认证先例）读 body 字节流 → `SessionOutputSink` 加 `storeBinary(sessionId, fileName,
  bytes, contentType)`；session 模块实现：查会话得归属（createdBy → workspaceOwner
  回退）→ `AttachmentStore.store`（PRIVATE，`tags=session-output`，description 记
  sessionId+fileName）→ session_outputs 行写 `attachment_id`（content 置 null）。
  覆盖语义：旧行带 attachmentId → `AttachmentStore.delete` 删旧附件（删除失败 warn
  不阻塞）；文本行 ↔ 附件行互覆时清另一列/删旧附件。
- **FR-04 列表与读取语义**：`GET /sessions/{id}/outputs` 列表项加 `attachmentId`
  （非空 = 附件产出），sizeBytes = 附件真实字节数。文本内容端点命中附件行返回 400
  「二进制产出不支持文本预览，请下载」。
- **FR-05 前端产出弹窗**：附件项——行内「推送」按钮换成「下载」（直链
  `attachmentRawUrl(attachmentId)`，新窗口/下载由 raw 端点 Content-Disposition 决定，
  图片 inline 其余 attachment）；选中后图片类 inline 渲染 `<img>`，其余显示「二进制
  文件不支持预览」+ 下载按钮；不发起文本预览请求。
- **FR-06 推送文档兜底闸**：`publishOutput` 命中附件产出返回 400「二进制产出不能推送
  为文档，请下载使用」（前端已隐藏入口，此为直调兜底）。

## 3. 否决方案

- **base64 走既有 JSON 通道 + session_outputs 加字节列（v1 方案）**：DB 塞二进制、
  附件管理体系旁路（无 scope/过期/管理页可见性）、下载要新开端点——用户决策否决，
  二进制交付物本质是附件，应进附件管理。
- **扩展名白名单判定二进制**：清单必然漏判，GBK 文本会被误判；UTF-8 严格解码与存量
  文本行为完全兼容且零维护。
- **multipart 表单上传**：runner 瘦 jar 手拼 multipart 边界体，相对裸字节 POST 零收益
  （单文件、无表单字段混合场景）。
- **服务端新开产出下载端点**：attachments `/raw` 既有（inline/attachment 分流 +
  access_token 直链 + 权限判定），不重复造。
- **附件产出也登 WI 产物（registerWorkItemOutputs 的 SUMMARY）**：本期不动——附件行
  在产物登记循环里只取 fileName 挂 path=sessionId#fileName，正文回读为空会误导；
  附件行跳过产物登记（文本行照旧），后续如需「产出附件进产物清单」另行立 CAP。

## 4. 架构与模块归属

| 层 | 归属 | 内容 |
|---|---|---|
| runner | devmind-agent-runner | `OutputUploader` 两路：文本 JSON（不变）+ 二进制裸字节 POST |
| 接收 | devmind-agent | `AgentOutputController` 同簇新端点 `/attachment`（token 认证）→ sink.storeBinary |
| SPI | devmind-common | `AttachmentStore`（新，attachment 实现）；`SessionOutputSink` 加 `storeBinary` default 方法 |
| 编排/存储 | devmind-session | `SessionOutputService.storeBinary`（归属解析→附件存储→引用行+覆盖删旧）；`SessionOutputEntity.attachmentId`；`SessionManagerService` 列表/读取语义 |
| 附件 | devmind-attachment | `AttachmentService` InputStream 存储入口 + `AttachmentStore` 实现（含系统内部 delete） |
| 消费闸 | devmind-flow | `publishOutput` 附件行 400 兜底；`registerWorkItemOutputs` 跳过附件行 |
| 前端 | features/sessions | 产出弹窗附件项下载/图片预览/禁推送 |

依赖方向：agent →（common SPI）→ session →（common SPI）→ attachment，全部
ObjectProvider 探测，无反向依赖；attachment 未装配时 storeBinary 降级为错误提示
（同 outputSink 未装配先例）。

## 5. 数据模型

`session_outputs` 存量表加一列（ddl-auto=update，可空新列，存量部署无需手工 ALTER）：

- `attachment_id` VARCHAR(32) 可空——CAP-83 附件产出的 attachments.id 引用；null =
  文本产出（正文在 content）。与 content 恰一非空（storeBinary 写引用清 content，
  文本 store 写 content 清引用并删旧附件）。

attachments 表零改动（产出附件就是普通附件行：scope=PRIVATE、tags=session-output、
description 记 sessionId+fileName、uploadedBy=会话 createdBy）。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/agent/output/{sessionId}?token= | （不变）文本产出 JSON 通道 |
| POST | /api/agent/output/{sessionId}/attachment?name=&contentType=&token= | （新）二进制产出裸字节上传，节点 token 认证；响应 200 |
| GET | /api/sessions/{id}/outputs | （改）列表项加 `attachmentId`；附件行 sizeBytes=真实字节数 |
| GET | /api/sessions/{id}/outputs/{fileName} | （改）命中附件行 400 提示下载 |
| GET | /api/attachments/{id}/raw | （复用）附件下载/图片 inline（`?access_token=` 直链） |
| POST | /api/sessions/{id}/outputs/publish | （改）附件产出 400 兜底 |

## 7. 安全

- 文件名白名单不变（`[\p{L}\p{N}._-]{1,64}`，runner 与服务端双重校验，防路径逃逸）。
- 上传端点节点 token 认证（同 output 端点）；服务端按附件配置卡大小（20MB），
  multipart 上限约束不适用（裸字节端点不走 multipart 解析，Spring 默认请求体大小
  不受 multipart.max-file-size 限制——须在端点内自行卡流式长度，读超即 413/400，
  防无界 body 撑爆内存/磁盘）。
- 附件行 scope 恒 PRIVATE（服务端钉死，不信 runner 传参）；下载走 attachments raw
  既有权限判定（owner/ADMIN）。
- 覆盖删旧走系统内部删除（bypass 用户校验），仅限 storeBinary 覆盖路径调用。

## 8. 里程碑

- **M1 二进制回传全链路**：FR-01~06 一次交付（runner/服务端/前端 + 单测），
  部署顺序先服务端后 runner（自升级随后）。

## 9. 验收标准

1. worktree `.devmind/output/` 同时放 analysis.md（文本）与 xlsx（二进制），会话退出
   后两者都出现在 `GET /sessions/{id}/outputs`，xlsx 行带 attachmentId、sizeBytes =
   文件真实字节数。
2. xlsx 经 attachments raw 端点下载后字节级等于原文件（sha256 一致）；附件管理页
   该文件出现在会话发起者名下（PRIVATE，tags=session-output）。
3. 文本产出（analysis.md）内容与现状逐字节一致（无回归）；中文文件名二进制产出
   回传/下载正常。
4. 同名 xlsx 重跑覆盖：session_outputs 指向新附件，旧附件从磁盘与附件管理页消失。
5. 前端产出弹窗：xlsx 项显示下载、无推送按钮、不发起文本预览；png 产出选中即
   inline 预览；文本项行为不变。
6. 超 20MB 的二进制文件被跳过（runner warn），其余文件照常回传。
7. `mvn -q test` 全绿、`npx tsc -b` 通过。

## 10. 风险与开放问题

- **版本 skew**：新 runner + 老服务端 → attachment 端点 404，runner 记 warn 跳过该
  文件（其余产出不受影响）；反向完全兼容。部署顺序服务端先行即可。
- **误判残留**：理论上存在「恰为合法 UTF-8 的二进制文件」按文本落库——与现状行为
  一致，不引入新风险。
- **裸字节端点的大小闸**：必须在读流时卡长度（附件配置 20MB），不能依赖容器
  multipart 上限；实现时注意流式读取、超限即断（已列入 FR-02/安全节）。
- **worklog 镜像**：`WorklogOutputMirror` 只匹配 daily/weekly .md 文本产出，附件行
  天然不进镜像，无需改动。
- **产出附件的产物登记**：WI 会话附件产出暂不登 SUMMARY 产物（正文回读为空会误导），
  「产出附件进产物清单」留待后续 CAP。

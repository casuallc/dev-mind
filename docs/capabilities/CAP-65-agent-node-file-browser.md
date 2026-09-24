# CAP-65 Agent 节点文件浏览器

> 能力 ID：CAP-65 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-09-24
> 依赖 CAP-21（节点通道）、CAP-34（协议版本门控）、CAP-54（confined 路径校验先例）、
> CAP-37/57（runner 主动 HTTP 传字节先例）。
> 对 Agent 节点（runner 所在机器）提供文件浏览器：查看列表、打开预览、编辑保存、
> 上传、下载、重命名、删除——节点级根目录白名单限定访问范围。

## 1. 目的

日常运维/排障常要看节点机器上的文件（构建产物、日志、配置、worklog），目前的手段只有
会话内终端（CAP-58/59，限定会话代码目录 + 命令白名单），节点级「看看这个目录里有什么、
把文件拿下来/传上去/改两行」没有趁手工具，只能登机器。

本能力在 Agent 节点管理中内嵌文件浏览器：

1. **浏览**：白名单根目录下逐层列目录（名称/大小/修改时间）；
2. **打开/编辑**：文本文件在线预览与保存（≤512KB，简单文本编辑器）；
3. **上传/下载**：≤100MB 文件双向传输（HTTP 中转，流式不过内存）；
4. **重命名/删除**：同目录改名；删文件/目录（非空目录须显式递归确认）；
5. **安全边界**：节点级根目录白名单（服务端 DB 权威，ADMIN 可配），越界一律拒绝。

## 2. 功能需求

### FR-01 访问范围：节点级根目录白名单（服务端权威）

- `agent_nodes` 增 `file_roots` 列（JSON 数组串，可空）。节点详情 UI 可编辑
  （每行一个绝对路径），**仅 ADMIN**；校验：绝对路径、≤16 条、单条 ≤240 字符；
  `null` 请求字段 = 不动、空数组 = 清空。
- 白名单为空 = 文件浏览不可用（前端入口禁用 + runner 侧全部 op 拒绝
  「节点未配置白名单」）。
- **信任模型**：服务端 DB 为唯一权威，每个 `file` 帧携带 roots 全量下发，runner
  不做本地配置。理由：①写操作直接改节点文件系统，授权边界必须平台 ADMIN 单点
  控制，双源（DB + runner properties）会出现「平台已收窄、节点本地仍放行」的不一致；
  ②runner 对 file 帧的信任级与 launch/exec 帧同级（WS 已由节点 token 强认证）；
  ③改白名单 UI 即时生效，免登节点机器改配置重启。

### FR-02 文件操作集（WS `file` 帧，协议 v18）

单帧类型 `file` + `op` 字段（CAP-54 workspace_query 先例），上行 `file_ack`：

| op | 语义 | 上限 |
|---|---|---|
| `list` | 单层目录列表（跳过 `.git`，目录优先 + 名称忽略大小写排序，1000 条截断 truncated；条目 {name,dir,size,mtime}） | — |
| `read` | 文本读取（预览/编辑共用）；前 8KB NUL 嗅探拒二进制 | 512KB |
| `write` | UTF-8 文本保存（原子写：同目录临时文件 + move） | 512KB |
| `rename` | 同目录改名；newName 禁含 `/ \ :`，目标已存在拒绝 | — |
| `delete` | 删文件/空目录；非空目录须 recursive=true（递归删除前再次 confined 校验防竞态） | — |
| `upload` | 大文件写：runner 按 transferId 主动 HTTP 拉中转文件，sha256 校验后原子写 | 100MB |
| `download` | 大文件读：runner 读文件主动 HTTP 推到中转端点 | 100MB |

- `path` 恒为相对某个 root 的相对路径（`""` = 根本身）；绝对路径、`..` 逃逸、
  白名单外 root 一律 `ok=false`。深度上限 32。
- runner 路径校验（resolveUnderRoot）：root 规范化后**精确匹配**白名单一项
  （统一 `\`→`/`、去尾分隔符；Windows 盘符大小写不敏感）；normalize + startsWith
  防 `..` + toRealPath 防符号链接（**写操作目标不存在时对父目录做 toRealPath**）。
- 大小双侧校验：服务端组帧前一道（超限直接 400/409 不发帧），runner 再校验一道
  （防直连 WS 伪造帧）。

### FR-03 大文件走 HTTP 中转（WS 零阻塞）

WS 只过小 JSON 指令（≤512KB 场景），字节一律走 runner 主动发起的 HTTP
（CAP-34 上下文包 / CAP-37 产出上传 / CAP-57 模型包同模式）：

- **上传**：浏览器 multipart POST → 服务端落临时文件
  `file-transfers/{transferId}` → WS 下发 `file{op=upload, transferId, size, sha256}`
  → runner `GET /api/agent/files-transfer/{transferId}?token=` 流式拉取 → sha256
  校验 → 原子写 → `file_ack` → REST 返回，服务端删临时文件。
- **下载**：浏览器 GET → WS `file{op=download, transferId}` → runner
  `POST /api/agent/files-transfer/{transferId}?token=`（octet-stream + sha256 头）
  流式推 → 服务端落临时文件 → `file_ack` → REST 从临时文件流式回浏览器
  （Content-Disposition RFC5987，中文文件名不乱码）→ 删临时文件。
- 中转端点 permitAll + 控制器内 `resolveByToken` 判定（CAP-37 output 先例）。
- 临时文件登记 {nodeId, size, sha256, expiresAt}，用后即删 + 10min 过期 GC；
  节点断连时作废其未完成 transfer。

### FR-04 REST 端点（浏览器侧，`/api/agent-nodes/{id}/files`，全 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/list?root=&path=` | 目录列表 |
| GET | `/read?root=&path=` | 文本读取 → `{content,size}` |
| GET | `/download?root=&path=` | 文件下载（octet-stream + RFC5987 文件名） |
| PUT | `/content` | 文本保存，body `{root,path,content}`（≤512KB） |
| POST | `/upload?root=&path=` | multipart 上传（>100MB 409） |
| POST | `/rename` | body `{root,path,newName}` |
| POST | `/delete` | body `{root,path,recursive}` |

- GET 也敏感（可读节点白名单内任意文件）：SecurityConfig 在 `GET /api/**` 通用规则
  前加 `/api/agent-nodes/*/files/** → ADMIN`；POST/PUT/DELETE 由现有
  `/api/agent-nodes/**` ADMIN 规则覆盖。
- 节点离线 → 409（沿用 connector requireConnection 惯例）；runner 协议 <v18 →
  409 引导升级；roots 未配置 → 409 提示先配置。

### FR-05 前端（节点详情内嵌）

- NodeDrawer 增「文件访问根目录」Card：TextArea 每行一个绝对路径（草稿态防轮询
  覆盖，照 labels 编辑先例），保存即时生效；文案注明「留空 = 文件浏览不可用」。
- NodeDrawer 增「文件浏览」入口（roots 空或节点非 ONLINE 禁用），打开
  NodeFilesDrawer（width=960）：
  - 顶部：root 选择（多 root 时 Select，单 root 静态文本）+ 面包屑路径导航
    （段可点击）+ 刷新/新建文本文件/上传按钮（前端先拦 >100MB）；
  - 列表：FitTable，列 = 名称（目录可点进入）/大小/修改时间/操作；
  - 预览/编辑合一 Modal：TextArea 等宽字体（CAP-60 字体栈）；二进制或 >512KB
    只读 + 「下载查看」按钮；保存后刷新列表；
  - 删除 Modal.confirm（目录提示递归删除）；重命名 Modal + 命名校验；
  - 下载 blob 落盘（downloadRunnerPackage 先例）；错误一律 showError。

### FR-06 协议与兼容

- `AgentProtocol.CURRENT = 18`，新增 `FILE_FRAMES = 18`：file 帧属「必须认识」
  （老 runner 静默忽略会让 REST 空等超时），服务端 supports() 门控 409 引导升级。
- 上行 `file_ack` 被老服务端忽略不门控（新 runner + 老服务端仅功能不可用）。
- 并发语义 v1：无 per-path 锁，同文件并发写「最后写入胜出」，不承诺高并发
  （管理员工具定位）。

## 3. 关键设计

- **为什么大文件不走 WS**：agent WS 是全节点共用控制通道（launch/exec/terminal/
  心跳都走它），单帧 13.4MB base64 会把 synchronized 发送占住、心跳与其他指令
  排队；且服务端 WS 收帧缓冲（512KB）要放大一个数量级。仓库既定先例本来就是
  「WS 传指令、字节走 runner 主动 HTTP」（上下文包/runner 包/GB 级模型包/产出
  上传无一例外），本能力沿用，WS 通道零阻塞、上限还能放宽到 100MB 流式。
- **为什么 list/read/write 仍走 WS**：小 JSON 请求应答与终端 exec 同量级，
  512KB 缓冲内无阻塞之虞，省掉一次 HTTP 中转往返。
- **为什么白名单权威在服务端而不是 runner properties**：见 FR-01。file 帧与
  exec 帧信任级相同；exec 白名单在 runner 本地是因为「命令解释器在 runner」，
  而文件白名单是平台 ADMIN 的授权决策，收敛单点才可推理。
- **为什么编辑上限 512KB**：在线编辑定位是「改两行配置/看日志尾部」，大文件
  走下载；512KB 与 WS 缓冲同档，无需动容器配置。
- **为什么删除非空目录要显式 recursive**：误删保护；递归删除前 runner 再做一次
  confined 校验防 TOCTOU（校验后目标被换成符号链接）。

## 4. 插件化接口

- common：`AgentProtocol.FILE_FRAMES`（v18）；`AgentNodeConnector.file(nodeId,
  AgentFileRequest)` default 方法；`AgentFileRequest`（op/root/path/newName/content/
  recursive/transferId/size/sha256）与 `AgentFileResult`（ok/payload/error）DTO。
- runner：`FileHandler`（ops 分发 + resolveUnderRoot 校验 + 原子写 + HTTP 拉/推
  中转）；`WorkspaceQueryHandler.resolveConfined` 加 maxDepth 重载（旧签名委托
  MAX_DEPTH=8，FileHandler 传 32）。
- 服务端：`AgentConnectionRegistry.file(...)`（waiter 模式 + onFileAck +
  onDisconnect 清理 + v18 门控）；`AgentNodeWsHandler` 加 `file_ack` case；
  `AgentNodeFilesController`（浏览器 REST）+ `AgentFileTransferController`
  （中转端点）+ `FileTransferStore`（临时文件登记/GC）；`AgentNodeEntity.fileRoots`。

## 5. API 概要

| 端点/帧 | 方向 | 说明 |
|---|---|---|
| `file` / `file_ack` | 双向 | 文件操作请求应答（协议 v18 门控），op 见 FR-02 |
| `GET /api/agent/files-transfer/{transferId}?token=` | runner → 服务端 | upload 拉取中转文件（permitAll + token 判定） |
| `POST /api/agent/files-transfer/{transferId}?token=` | runner → 服务端 | download 推字节（permitAll + token 判定） |
| `GET /api/agent-nodes/{id}/files/list` | REST | 目录列表（ADMIN） |
| `GET /api/agent-nodes/{id}/files/read` | REST | 文本读取（ADMIN） |
| `GET /api/agent-nodes/{id}/files/download` | REST | 文件下载（ADMIN） |
| `PUT /api/agent-nodes/{id}/files/content` | REST | 文本保存（ADMIN） |
| `POST /api/agent-nodes/{id}/files/upload` | REST | multipart 上传（ADMIN） |
| `POST /api/agent-nodes/{id}/files/rename` | REST | 重命名（ADMIN） |
| `POST /api/agent-nodes/{id}/files/delete` | REST | 删除（ADMIN） |

## 6. 验收标准

1. 节点配置白名单后：list 根目录 → write 中文文本 → read 回读一致 → rename →
   delete，全链路成功；
2. upload 5MB 随机二进制 → download 回本地，sha256 比对一致（HTTP 中转链路）；
3. `../`、绝对路径、白名单外 root、符号链接逃逸：全部拒绝（ok=false / 4xx）；
4. 二进制文件 read → 拒（提示下载查看）；>512KB write → 409；>100MB upload → 409；
5. roots 为空：前端入口禁用，REST 409 提示先配置；
6. runner 协议 <v18：REST 409 引导升级；节点离线：409；
7. 非空目录 delete（recursive=false）拒绝，recursive=true 成功；
8. 非 ADMIN 角色访问 files 端点（含 GET）→ 403；
9. 中文文件名/含空格路径全链路正常，下载文件名不乱码；
10. 大文件传输期间同节点终端/心跳不受影响（WS 零阻塞）。

## 7. 落地状态

- **M1 —— 需求定稿**：本文档。

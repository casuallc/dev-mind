# CAP-71 Agent 节点审计日志（连接日志升级：全量交互留痕 + 分类检索 + 可配保留期）

> 能力 ID：CAP-71 ｜ 分类：底座 ｜ 状态：草案（需求定稿 2026-10-10）
> 演进自 CAP-21 FR-01 的「接入/拒绝/断线落审计」（`agent_conn_logs`，仅三类流水、
> 保留 7 天硬编码）；覆盖 CAP-34/36/42/54/57/58/59/65/70 引入的全部节点交互面。

## 1. 目的

runner 能力面快速扩张后，「Agent 执行」下的连接日志仍只有接入/拒绝/断线三类流水，
以下交互**全部只进服务端日志文件，无法审计追溯**：

- **代理转发**（CAP-70 出口隧道）：谁、何时、经哪个节点、访问了哪个内网目标、
  传了多少字节——白名单拒绝（未命中规则）与命中不可用也只在日志文件里；
- **指令下发**：launch/finish/kill/exec/文件/终端/进程管控/包分发/升级等二十余种
  下行指令及其 ack 结果；
- 隧道起落（`/ws/agent-tunnel` 的 CONNECT/DISCONNECT 从未落库）、上下文包拉取、
  文件中转、出口规则变更、节点运维操作。

本能力把连接日志升级为**节点域全量审计日志**：分类 + 服务端真分页 + 关键词过滤 +
可配置保留期（默认 180 天），替代原「最新 200 条、保留 7 天」的流水视图。

与 CAP-07 FR-06 的 `audit_logs`（执行审计，面向构建/部署等执行器动作）正交：
本表面向 **agent 节点交互**（连接/指令/隧道/代理），条目更细、量更大，独立成表。

## 2. 功能需求

### FR-01 全量分类留痕

新表 `agent_audit_logs`，九个分类（category）：

| category | event（示例） | 语义 |
|---|---|---|
| CONNECTION | CONNECT / REJECT / DISCONNECT / KICK_DUPLICATE / WATCHDOG_TIMEOUT / EVICT | 控制通道（/ws/agent）起落与主动踢除 |
| TUNNEL | CONNECT / REJECT / DISCONNECT / DROP_SILENT / ILLEGAL_FRAME | 隧道通道（/ws/agent-tunnel）起落、静默断链摘除、非法帧断连 |
| COMMAND | LAUNCH / INPUT / AUTHORIZE / FINISH / KILL / SUSPEND / EXEC / COLLECT / WORKLOG_PUSH / WS_FINALIZE / WS_RELEASE / WS_QUERY / TERMINAL_EXEC / TERMINAL_COMPLETE / PROC / PKG / UPGRADE / FILE_OP | 下行指令：带 ack 的在收口记录（result=ack 结果、durationMs=等待耗时）；fire-and-forget 下发即记（result=OK 语义=已下发）；launch ack 失败补记 FAIL |
| PROXY | OPEN / REJECTED_NO_RULE / FAIL_UNAVAILABLE | **代理转发逐条记录**：OPEN 在流终结时落一条（bytesUp/bytesDown/durationMs）；未命中规则拒绝（nodeId 空）；命中但隧道不可用/OPEN 超时记 FAIL |
| CONTEXT | PULL | runner 拉取上下文包（成功/失败，target=sessionId） |
| FILE_TRANSFER | UPLOAD / DOWNLOAD | CAP-65 文件中转端点 |
| RULE | CREATE / UPDATE / DELETE | 出口规则变更（带 actor） |
| PACKAGE | UPLOAD / DOWNLOAD | runner 包托管上传/下载（带 actor 或节点名） |
| NODE | CREATE / UPDATE / DISABLE / ENABLE / DELETE / SET_DEFAULT | 节点运维操作（带 actor） |

写入纪律：**旁路同步落库**（`repo.save` 整体 try-catch，失败只 log.warn），
绝不阻塞 WS 派发线程与隧道 relay 线程主流程。INPUT 事件 detail 只记
`len=N, images=M`，**不记用户输入内容**；所有 detail 禁凭证。

### FR-02 代理流终结审计（含统一终结点改造）

- `ServerTunnelStream` 增 bytesUp/bytesDown 计数（localToTunnel 发送后累加 up、
  tunnelToLocal 落盘后置加 down）与 openedAt。
- 补**幂等统一终结点**：现状正常收尾两 relay 线程各自 return（不摘流表、不关
  socket、不走 abort），改造为正常/异常路径都汇入统一终结（摘表 + 关 socket +
  触发一次审计回调，AtomicBoolean 保证恰好一次）——顺带修复正常收尾不摘表/不关
  socket 的存量缺口。
- 审计回调由建流方（Socks5Server/HttpConnectProxyServer）注入；detail 带协议形态
  （socks5 / http-connect / http-absolute）。

### FR-03 查询 API（服务端真分页）

`GET /api/agent-nodes/audit-logs?category=&event=&nodeId=&result=&keyword=&page=0&size=20`
→ `PageView<AgentAuditLogView>`（复用 `com.devmind.common.dto.PageView`，page 0 起、
size≤200，与 /api/audit-logs 同约定）。

- 过滤：category / event / nodeId / result 精确匹配（可空 = 不限）；
- keyword：对 detail / nodeName / remoteAddr / target 四列做大小写不敏感包含
  （全部普通 varchar，无 @Lob lower() 红线）；
- 排序：created_at desc, id desc。

原 `GET /api/agent-nodes/conn-logs` 端点**删除**（前端是唯一消费方，随前端一并切换）。

### FR-04 保留策略（可配置）

- `devmind.agent.audit-retention-days`（默认 **180** 天）；
- `devmind.agent.audit-cleanup-cron`（默认 `0 20 3 * * *`）；
- 定时任务按保留期硬删（`deleteByCreatedAtBefore`），删除数 >0 才记日志
  （AttachmentCleanupTask 模式）。

### FR-05 前端「审计日志」页

「Agent 执行」组下原「连接日志」页升级为「审计日志」：

- 筛选行：分类 / 节点 / 结果 Select + 关键词 Input.Search（变更即回第 0 页）；
- 服务端真分页表格（FitTable，showSizeChanger + showTotal），**去掉 5s 轮询**
  （分页语义下轮询会跳页），刷新按钮在 Card extra；
- 列：时间 / 分类 Tag / 事件 / 节点 / 操作人 / 目标 / 结果 Badge / 耗时 /
  流量(↑↓) / 详情（ellipsis，点击 Drawer 看全文）；
- 路由改 `/admin/agent/audit-logs`，旧 `/admin/agent/logs` 重定向保留；
  菜单文案「连接日志」→「审计日志」。

## 3. 关键设计（已定）

- **新表不更名**：`agent_conn_logs` 不改名扩建而直接新建 `agent_audit_logs`——
  ddl-auto=update 不删表，旧表成为死表（存量部署可手工 `DROP TABLE agent_conn_logs`）；
  旧数据本就 7 天保留，无迁移价值。
- **同步写不异步**：代理逐条写在流终结点（每连接一条），指令写在 ack 收口，
  频率与现有连接流水同量级；repo.save 失败仅降级 log.warn，不引入队列/批量
  （量级达不到，复杂度不值）。
- **不记内容只记摘要**：INPUT 不记文本（len/images 计数）；exec detail 记命令摘要
  与 exitCode；代理只记目标/字节/耗时，不截 payload。
- **与执行审计的分工**：构建/部署等 exec 已在 `audit_logs`（domain=agent_exec）
  有一份面向执行器的记录；本表 COMMAND/EXEC 是面向节点的交互视角（哪条指令发到
  哪个节点、ack 如何），两者入口与粒度不同，并存不冲突。

## 4. 数据模型

`agent_audit_logs`：

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint identity | |
| created_at | timestamp | 非空，索引 |
| category | varchar(16) | 九分类，索引（category+created_at） |
| event | varchar(32) | 分类内事件 |
| node_id | bigint | 可空（REJECT / 代理未命中规则） |
| node_name | varchar(128) | 快照，节点删除后日志仍可读 |
| actor | varchar(64) | 管理操作人（RULE/PACKAGE/NODE），WS 交互为空 |
| remote_addr | varchar(64) | WS 来源 IP:端口 |
| target | varchar(255) | 代理 host:port / sessionId / transferId / 规则 pattern |
| result | varchar(16) | OK / FAIL / REJECTED |
| bytes_up / bytes_down | bigint | 仅代理流 |
| duration_ms | bigint | 代理流 / 带 ack 指令 |
| detail | varchar(512) | 摘要（普通 varchar，关键词可 lower like） |

索引：`created_at`、`(category, created_at)`、`(node_id, created_at)`。

## 5. 验收标准

- 起 runner 连接/断开/隧道起落、launch 会话、发起一次经出口隧道的 git fetch，
  审计页分别能按分类检索到对应记录；代理记录含目标 host:port、字节数、耗时。
- 未命中规则的代理请求在审计页可见（result=REJECTED，nodeId 空）。
- 分页（跨页翻页/换页大小）、关键词（命中 detail/nodeName/target 任意列、大小写
  不敏感）、分类/节点/结果过滤均生效且 total 正确。
- `devmind.agent.audit-retention-days=1` 时清理任务删除超期行；默认 180 天。
- 存量部署（PG/MySQL）升级：ddl-auto 自动建 `agent_audit_logs`，旧
  `agent_conn_logs` 变死表（可手工 DROP），无迁移脚本。

## 6. 分期与边界

- 单期交付。非目标：审计日志的导出/外发、跨模块统一审计中心（audit_logs 与
  本表保持各自入口）、runner 侧操作审计（runner 本地行为归 runner 日志）。

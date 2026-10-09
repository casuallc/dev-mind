# CAP-70 服务端出口反向隧道（按域名规则经节点代理访问内网）

> 能力 ID：CAP-70 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-10-09
> 依赖 CAP-21（节点 WS 通道/token 认证）、CAP-34（协议版本门控）、CAP-43（节点侧代理的正交先例）、
> CAP-65（file 帧 waiter/白名单服务端权威先例）。

## 1. 目的

部署形态「**服务端在外网、代码与研发系统在公司内网、内网 runner 出向 WS 注册**」下，
执行类功能（会话/问答/构建/部署/测试，CAP-34/36）全部下发 runner，不受影响；但
**服务端自己直连内网资源**的功能整片失效：

- **git 直连**（`GitRemoteOps`，devmind-integration）：项目/全局库克隆（CAP-23/29）、
  定时 fetch 与分支列表刷新（CAP-29）、执行前同步（CAP-26，build/release/worktree 消费）、
  CAP-31 远程会话 diff（服务端 fetch 会话分支到本地缓存）、push 分支/tag（CAP-18）、
  ls-remote 凭证自检（CAP-24）；
- **Integration REST API**：GitLab/GitHub/Jira/飞书连接器的 testConnection/listProjects/
  createMergeRequest/createRelease、Jira 轮询同步（CAP-19）、需求推送 Jira（CAP-47）、
  飞书知识导入（CAP-45）；
- **杂项 HTTP**：书签连通性探测（CAP-64）。

而「有的远端要走内网出口、有的（外网 GitHub 等）直连」是**运维决策，不该写进代码**。
本能力提供**通用反向隧道**：runner 出向建立专用隧道通道，在服务端本机落成
`127.0.0.1` SOCKS5 端点，服务端按**平台级域名规则表**逐请求决定直连还是经哪个节点
的隧道出访。

与 CAP-43 正交：CAP-43 是 runner→外网走本机代理（方向 runner→Internet）；本能力是
服务端→内网借 runner 出口（方向 server→Intranet），两者可叠加、互不感知。

非目标：UDP 转发；隧道出口侧代理认证；模型端点/分类边车（CAP-48/49/57）接入
（演进项）；通知渠道（企微/Bark 出公网，外网部署天然可用）。

## 2. 功能需求

### FR-01 隧道通道（runner 出向，专用连接）

- runner 除 `/ws/agent` 控制通道外，另起一条**专用隧道 WS 连接**（`/ws/agent-tunnel`，
  二进制帧），token 认证与控制通道同级；断线指数退避自动重连；一节点一条隧道。
- 流多路复用：帧 = `{streamId, type, host?, port?, payload?}`，type ∈
  `OPEN / OPEN_ACK / DATA / CLOSE / RST`；DATA 帧 ≤32KB。
- **背压流控**：每流在途窗口（默认 256KB），超窗暂停读 TCP 侧；防大库 clone 时
  快侧撑爆慢侧内存。
- 协议版本门控：`AgentProtocol.EGRESS_TUNNEL = 21`，hello 的 protocolVersion 已上报，
  服务端据此判定节点能否建隧道（<21 不建、规则引用它时给出升级提示）。

### FR-02 服务端 SOCKS5 端点（仅本机）

- 服务端内嵌 SOCKS5 server，**只绑 `127.0.0.1`**（禁 0.0.0.0，无认证——消费方全是
  同机进程）；端口 `devmind.egress.socks-port` 可配，默认 18089。
- 收到 CONNECT：目标 host 查规则表 → 命中且对应节点隧道在线 → 经该隧道 OPEN →
  双向 relay；命中但隧道离线 → 立即拒绝并记日志（不挂起等待）；**未命中 → 拒绝**
  （SOCKS5 not-allowed），不放行任何未配规则的目标出隧道。
- DNS 语义：客户端一律用 socks5h（主机名不透传解析，由 runner 侧解析）——
  服务端在外网本就解析不了内网域名。

### FR-03 平台级规则表（服务端 DB 权威，即白名单）

- 新表 `egress_rules`：host glob（`gitlab.corp.com` / `*.corp.com`，大小写不敏感、
  不区分端口）→ 出口节点（agent_nodes.id）+ enabled + remark + 排序；
  仅 ADMIN 可配。未命中任何规则的 host = 直连（零行为变化）。
- **规则表即安全白名单**：只有命中规则的 host 才可能进隧道，「开放任意内网出口」
  的风险在配置层收敛；不支持 noProxy 语义（不配规则 = 天然直连）。

### FR-04 runner 侧二次校验

- 隧道握手时服务端向 runner 推送**该节点放行的 host 快照**（规则变更时重推）；
  runner 对每个 OPEN 帧的目标 host 再校验一遍，不命中 → RST。防控制面被绕过、
  伪造 OPEN 帧把 runner 变成任意内网跳板（同 CAP-65 file 帧双侧校验哲学）。

### FR-05 git 出口注入（GitRemoteOps）

- `GitRemoteOps` 组命令前按 remoteUrl host 查规则：命中 → 命令行注入
  `-c http.<url>.proxy=socks5h://127.0.0.1:<port>`（per-URL 精确生效、命令级生命周期，
  同 CAP-43 FR-05 的 `-c` 注入先例；file:// 与纯本地操作天然免疫）；未命中零改动。
- 全部消费点零改动受益：CAP-23/29 克隆、CAP-29 定时 fetch、CAP-26 执行前同步、
  CAP-31 远程 diff、push 分支/tag、ls-remote 自检。

### FR-06 Java HTTP 出口注入（ProxySelector）

- 新增规则驱动 `ProxySelector`（命中 → 本机 SOCKS5，未命中 → DIRECT）；
  GitLab/GitHub/Jira/飞书连接器与 BookmarkProbeService 的 HttpClient/RestTemplate
  **显式挂载**，不设 JVM 全局默认（防意外流量被全量导进隧道）。
- 由此零改动受益：testConnection/listProjects/createMR/createRelease、Jira 轮询同步、
  需求推送 Jira、飞书导入、书签探测。

### FR-07 失败语义（fail-visible，不静默回落）

- 命中规则但节点离线/隧道断开/OPEN 被拒 → **快速失败**，错误文案带规则名与节点
  状态（如「出口节点 build-224 离线」）；**禁静默回落直连**（静默 = 排查黑洞，
  同 CAP-43 FR-04 哲学）；REST 层呈现 409/503 与引导文案。

### FR-08 前端

- 节点管理新增「出口规则」卡片/页签：规则 CRUD（host glob + 节点选择 + 启用开关 +
  备注）、每节点隧道在线状态徽标；规则指向协议 <v21 节点时保存即警告「runner 需升级」。

### FR-09 兼容性

- 无规则 = 功能整体关闭，存量行为零变化；老 runner（<v21）不建隧道，控制通道与
  既有全部帧不受影响；规则引用老 runner = 该规则覆盖的流量按 FR-07 失败并提示升级。

## 3. 关键设计

- **为什么通用反向隧道而非 typed 代理帧**：需求本质是「按域名选择出口」的运维配置，
  typed 帧（gitproxy/httpApi 逐 op 建模）每接一个功能就要扩一次协议，且无法覆盖
  「任意 HTTP 客户端」；隧道把策略（规则表）与机制（字节转发）分离，git/REST/未来
  客户端共用一条通道。
- **为什么反向**：runner 在 NAT/防火墙后只有出向连接，服务端无法主动 TCP 连 runner，
  正向代理物理不成立；隧道复用 runner 出向 WS，服务端侧落本机 SOCKS5 端点。
- **为什么 SOCKS5 而非 HTTP CONNECT**：git `http.proxy` 原生支持 socks5h（DNS 在
  代理侧解析，服务端无法解析内网域名这一点必须靠它）；Java `Proxy.Type.SOCKS`
  原生支持；无需 UDP。
- **为什么独立 WS 通道**：控制通道是全节点共用指令通道（CAP-65 §3 已论证大字节
  不得占用），隧道是双向持续字节流，专用二进制连接 + 流多路复用，控制通道零阻塞。
- **为什么自研 Java 隧道**：帧格式仅 OPEN/ACK/DATA/CLOSE/RST 五种 + 窗口流控，
  无外部二进制依赖、runner 瘦 jar 形态与托管自升级体系（退出码 42）不受冲击；
  引入 chisel/frp 类二进制需按平台分发并托管子进程生命周期，与现状摩擦大。
- **为什么规则表即白名单 + runner 二次校验**：授权边界平台 ADMIN 单点控制
  （同 CAP-65 file_roots 权威模型）；runner 持快照再校验，服务端被绕过也不成跳板。
- **为什么禁静默回落直连**：规则命中即「用户明确声明此 host 须走内网」，直连失败
  与隧道失败混为一体会让配置错误表现为偶发超时。

## 4. 插件化接口

- common：`AgentProtocol.EGRESS_TUNNEL`（v21）；`EgressProxyRouter` SPI——
  `Optional<Proxy> proxyFor(String host)`（Java 侧）与 `Optional<String> gitProxyUrl(String remoteUrl)`
  （git 侧），由 devmind-agent 实现（规则表 + 隧道状态 + SOCKS 端口都在该模块），
  消费方 `ObjectProvider<EgressProxyRouter>` 探测注入，缺席 = 全直连（兼容无 agent
  模块的装配形态）。
- devmind-agent：`egress_rules` 实体与 CRUD、`/ws/agent-tunnel` 端点与流复用/路由、
  内嵌 SOCKS5 server、规则快照推送。
- devmind-agent-runner：隧道客户端（出向连接 + OPEN 时出向 TCP 拨号 + 窗口流控 +
  快照校验）。
- devmind-integration：`GitRemoteOps` 组命令注入；各连接器 HttpClient 挂 selector。
- devmind-bookmark：探测客户端挂 selector。

## 5. 数据模型与协议

**egress_rules**（ddl-auto=update 自动建表）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| host_pattern | varchar(255) | host glob，小写规范化存储 |
| node_id | bigint | 出口节点（弱关联 agent_nodes.id） |
| enabled | boolean | 默认 true（实体初始值，不加 @ColumnDefault） |
| sort | int | 匹配顺序（先命中先生效） |
| remark | varchar(255) 可空 | |
| created_at / updated_at | timestamp | |

**隧道握手**：runner 连 `/ws/agent-tunnel?token=` → 服务端下发 `tunnel_hello
{protocolVersion, allowedHosts[]}` → 之后纯流帧。规则变更 → 重发 `tunnel_hello`
（全量快照，量小无需增量）。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/egress-rules | 规则列表（ADMIN） |
| POST | /api/egress-rules | 新建（ADMIN） |
| PUT | /api/egress-rules/{id} | 编辑（ADMIN） |
| DELETE | /api/egress-rules/{id} | 删除（ADMIN） |
| GET | /api/egress-rules/status | 各节点隧道在线状态（ADMIN） |
| WS | /ws/agent-tunnel | runner 隧道通道（token 认证） |

## 7. 验收标准

1. 配规则 `gitlab.corp.com → 内网节点`、runner v21 在线：服务端 `git ls-remote`
   内网库成功；CAP-23 克隆、CAP-29 定时 fetch、CAP-31 远程 diff 全链路恢复；
2. 未配规则的外网库（GitHub）：全部操作直连，行为与现状一致；
3. 内网域名服务端本地 DNS 无法解析时（socks5h）仍通；
4. Integration testConnection（GitLab）与书签探测命中规则走隧道成功；删规则后
   报明确失败而非超时；
5. 隧道断线：命中规则的 git/HTTP 操作快速失败、文案含节点状态；重连后自动恢复；
6. runner <v21 被规则引用：操作失败提示「runner 需升级」，不静默；
7. 伪造 OPEN 帧目标不在节点快照内：runner RST，服务端收到明确错误；
8. 大仓库经隧道 clone 期间，同节点控制通道（心跳/launch/file 帧）不受影响；
9. 非 ADMIN 访问 /api/egress-rules**（含 GET）→ 403。

## 8. 分期与 MVP 边界

- **M1（本期）**：FR-01~09 全量——git 出口 + Integration REST + 书签探测；
  单隧道单流窗口流控；规则仅平台级。
- **演进**：模型端点/分类边车（CAP-48/49/57）HTTP 出口接入；HTTP CONNECT 端点
  （兼容只支持 http proxy 的客户端）；按 Integration/仓库绑定出口节点的细粒度规则；
  隧道带宽/流量统计（对接 CAP-67）；一节点多隧道并行提升大库吞吐。

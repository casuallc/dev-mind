# CAP-21 远程 Agent 节点管理（Remote Agent Runner）

> 能力 ID：CAP-21 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-02

## 1. 目的

平台部署在 Linux 服务器后，仍能调度 **Windows 开发机上的 Claude Code 等 agent** 开启会话。
Windows 办公机普遍在 NAT/防火墙后、入站不可达，因此采用**反向连接**模式：
节点侧跑一个轻量 **agent runner**（Java 瘦 jar），主动以 WebSocket 长连接注册到服务端，
服务端经此连接下发会话指令，runner 在本地拉起 agent 子进程并把**解析好的事件流**回传。

```
浏览器 ⇄WS⇄ Linux devmind-app ⇄WS 长连接（runner 发起）⇄ Windows agent-runner ⇄spawn⇄ claude 子进程
```

**事件解析下沉 runner**（已定）：runner 复用 `devmind-session` 的 `CliProcessLauncher` +
`CliEventParser`，claude stream-json 的 schema 接触点只在 runner 一侧，CLI 版本升级
不需要动服务端；服务端只面对稳定的内部 `SessionEvent` 协议，对 CLI 细节无感。

**runner = Java 瘦 jar**（已定）：不换语言——事件解析在 runner 意味着解析器是 runner 的
核心资产，直接复用 Java 实现避免双端各维护一份。节点机装 JRE 21 即可（jlink 免安装包属后续）。

## 2. 功能需求

- **FR-01 节点注册与认证**：服务端「节点管理」生成注册 token（只显一次，库存哈希）；
  runner 配置 `serverUrl + token`，启动时连 `WS /ws/agent` 注册：
  上报 name / os / labels / capabilities（可用 agent 种类，如 claude）/ runner 版本。
  非法 token 拒绝接入并落审计。
- **FR-02 心跳与在线状态**：runner 周期心跳（默认 15s）；超时未心跳标记 OFFLINE，
  该节点上的 RUNNING 会话标记失联（不直接判 FAILED，等节点重连上报真实结局）；
  断线自动重连（指数退避），重连后服务端按会话清单对账。
- **FR-03 远程会话调度**：`sessions` 加 `agent_node_id`（NULL = 本地，现状兼容）；
  创建会话指定目标节点 → 服务端经节点连接下发 `launch` 指令
  （sessionId / workdir / taskSpec / model / permissionMode）。
  节点离线时创建请求直接失败（409 + 明确提示），不产生挂死会话。
  **项目可配默认执行节点**（`projects.agent_node_id`，空 = 继承平台默认）；
  **平台可配全局默认执行节点**（`agent_nodes.is_default`，全平台至多一个，2026-09-03 补）。
  有效节点优先级：会话显式指定 > 项目默认 > 平台默认 > 本机——适配「服务端部署在无 AI
  能力的 Linux 机器」场景：部署后注册一个 runner 节点并设为平台默认，所有会话即自动调度
  到该节点，本机无 claude 也完全可用。平台默认节点离线时创建失败（409 明确提示），不静默
  回落本机；禁用/删除默认节点自动清除标记。
- **FR-04 事件回传与中继**：runner 本地完成 stream-json 解析，把 `SessionEvent`
  （含 state 翻转）流式回传；服务端中继三处：落库（`session_events`）、
  WS 广播给前端订阅者、驱动服务端会话状态机。前端对本地/远程会话**无感知**，
  同一套看板与终端视图。
- **FR-05 远程交互**：`input` / `authorize` / `kill` / `suspend` 指令随节点连接下发，
  runner 映射为本地子进程操作（写 stdin / permission_result / 杀进程树）。
  指令必须带 sessionId 且校验该会话归属此节点。
- **FR-06 远程工作目录**：workdir 语义 = **节点本地路径**。MVP 由节点侧配置
  「项目 → 本地路径」映射（runner 配置文件）；平台不做代码同步，仓库由节点机自行维护。
- **FR-07 节点管理 UI**：`features/agent` 自包含目录——节点列表（在线状态/os/版本/
  最近心跳/运行中会话数）、生成注册 token、禁用/删除节点；创建会话表单加「执行节点」
  下拉（默认本地）。**一键安装脚本**（2026-09-03 补做）：创建节点对话框直接下载
  Windows `.ps1` / Linux `.sh` 一键脚本（内嵌 serverUrl+token，前端生成，依赖下载端点的
  节点 token 认证，后端零改动）；节点行内另提供参数化脚本（token 运行时传参）。
  脚本流程 = 检查 java(21+) → 下载 runner 包 → 写 agent.properties → 后台常驻启动
  （Windows `Start-Process -WindowStyle Hidden`，Linux `nohup`，日志落 runner.log）。
- **FR-08 runner 瘦包**：独立可执行 jar（只含 session runtime 依赖子集 + WS 客户端），
  `java -jar devmind-agent-runner.jar [agent.properties]` 启动（args[0] 为配置文件路径，
  缺省 `./agent.properties`，properties 格式）；配置项 = serverUrl(必填) / token(必填) /
  workDir / permissionMode / executor / 项目路径映射(project.<id>) / claude 路径(claudePath) /
  并发上限(maxConcurrent)。
- **FR-09 runner 版本维护与手动升级**（2026-09-03 补做）：节点页「Runner 包」页签上传
  `devmind-agent-runner.jar` 由服务端托管（**全局单份，上传即替换**；版本从包内
  `runner-version.txt` 提取、sha256 入库；强校验含 `SelfUpdater.class`，无自升级能力的
  旧包拒收）。节点列表行内「升级」按钮（仅 ONLINE）→ 服务端 WS 下发 upgrade 指令 →
  runner 校验后由 **SelfUpdater 独立进程**完成换包重启（Windows 运行中 jar 不可覆盖）。
  **有活跃会话时 runner 回 busy 推迟，不杀会话**。在线老版本 runner 不认识 upgrade 帧，
  需先手工部署到含升级能力的基线版本；升级后日志落 jar 旁 `runner.log`（首次手工启动
  仍是控制台）。hello 版本自动比对升级仍不做。

## 3. 插件化接口

- 服务端 `SessionExecutor` SPI 新增远程实现（CAP-05 预留的扩展点）：
  `launch()` 不再返回本机 `Process`，而是经节点连接发指令、以事件流构造
  逻辑会话句柄（写 stdin = 发 input 指令，读 stdout = 收 event 帧，destroy = 发 kill）。
  现有 `CliProcessLauncher` / `FakeProcessLauncher` 零改动。
- runner 侧复用同一 SPI：`CliProcessLauncher` + `CliEventParser` 原样打进瘦 jar。

## 4. 依赖关系

- 依赖：CAP-01（节点管理 UI 鉴权；runner 接入用独立 token，不走用户 JWT）、
  CAP-05（会话模型/状态机/事件流，远程会话复用同一套）。
- 被依赖：CAP-15/17 编排层（调度时可选目标节点）。

## 5. 数据模型

```
agent_nodes(id, name, token_hash, os, labels, capabilities, runner_version,
            status[ONLINE|OFFLINE|DISABLED], last_heartbeat_at, created_at,
            is_default)                            # 平台默认执行节点，全平台至多一个
runner_packages(id=1 单行, version, sha256, size_bytes, original_filename,
                uploaded_at, uploaded_by)        # FR-09：全局单份托管包，jar 本体在 data/agent-runner/runner.jar
sessions  ── + agent_node_id (NULL=本地)
projects  ── + agent_node_id (NULL=本机；项目默认执行节点，会话创建未指定时继承)
```

WS 协议（JSON 帧， runner ⇄ 服务端双向）：

```
↑ register{name,os,labels,capabilities,version} / heartbeat / event{sessionId,seq,...} / state{sessionId,state,reason} / exit{sessionId,code,ok,summary}
  upgrade_ack{ok, reason?, activeSessions?}    # FR-09：busy=有活跃会话推迟；ok=true 后 runner 即退出换包
↓ launch{sessionId,workdir,taskSpec,model,permissionMode} / input{sessionId,text} / authorize{sessionId,requestId,accepted,scope} / kill{sessionId} / suspend{sessionId}
  upgrade{version, sha256, sizeBytes}          # FR-09：手动触发；runner 下载校验后才回 ack
```

## 6. API 概要

```
POST   /api/agent-nodes                 创建节点 + 生成注册 token（只显一次）
GET    /api/agent-nodes                 节点列表（含在线状态/最近心跳/会话数）
POST   /api/agent-nodes/{id}/disable | /enable | DELETE
POST   /api/agent-nodes/{id}/default | /unset-default   设为/取消平台默认执行节点（设定时清除其他节点标记）
WS     /ws/agent                        runner 接入端点（token 认证）
POST   /api/sessions                    现有端点 + agentNodeId?（缺省=本地）
# FR-09 runner 包（上传/升级仅 ADMIN；下载 permitAll + 控制器内「节点token 或 登录态」双认证）
POST   /api/agent-nodes/runner-package           multipart 上传替换（提取版本+sha256，强校验 SelfUpdater）
GET    /api/agent-nodes/runner-package           当前包元数据（无包 404）
GET    /api/agent-nodes/runner-package/download  jar 流（runner 升级用 ?token=；管理页用 JWT）
POST   /api/agent-nodes/{id}/upgrade             手动升级，恒 200：status=ACCEPTED/BUSY/ALREADY_LATEST/REJECTED
```

## 7. 验收标准

- Linux 服务端 + Windows runner：注册上线 → 看板可见节点 ONLINE；
- 指定该节点创建会话，浏览器实时看到 claude 输出流，与本地会话体验一致；
- 等待输入/授权状态正确翻转，可远程注入输入与授权；
- 杀掉 runner 进程 → 节点 OFFLINE、会话标记失联；重启重连后对账恢复；
- 本地会话（agent_node_id 为空）行为零回归。

## 8. MVP 范围（暂不做）

标签调度/负载均衡（创建会话手工选节点）、hello 版本自动比对升级（FR-09 已做手动升级）、
jlink 免安装包、代码同步（worktree 由节点机自理）、多 agent 种类的能力协商 UI、节点间会话迁移。

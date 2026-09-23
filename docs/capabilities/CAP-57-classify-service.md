# CAP-57 分类服务（Classify Service）：laya 边车平台化管控与业务流程解耦

> 状态：**需求定稿（2026-09-23）**，待实施。

## 1. 目的

laya 决策模型在知识库分诊场景经 CAP-56 真机基准证伪（37 题基准分类腿 accuracy 0.216 < 随机 0.389），
嵌在业务流程里做闸门是负价值。但「state + questions → 分类/打分/noul」的能力形态适合未来的
**工单分类、邮件分类**等场景。本能力做两件事：

1. **解耦**：决策引擎与所有业务流程脱钩（知识库提案分诊摘除 DecisionEngine 调用，走纯人工路径），
   决策链路降级为平台级独立「分类服务」，不向任何业务链路承诺 SLA；
2. **平台化**：Python 边车（tools/laya-sidecar，FastAPI uvicorn）的**生命周期（起停/健康）**与
   **安装包（程序包/模型权重包/语料包）**纳入管控台，经 agent 节点 WS 通道下发执行，
   替代 `tests/cap56_gpu_e2e.py` 里 SSH `pkill uvicorn` + `nohup` 的手工运维。

### 非目标（v1 不做）

- 边车崩溃自动拉起 / 看门狗守护（UNHEALTHY 标红，人工或手动重启）；
- 大文件分片 / 断点续传上传（模型权重包走整包流式上传，multipart 上限提到 4GB）；
- 节点侧安装包卸载与磁盘回收（`pkg` 帧 v1 只有 install）；
- MODEL_WEIGHTS 安装后自动登记 decision_checkpoints（v1 手动复制 installDir 到 CAP-56 登记表单）；
- playground 结果的人工裁决回写（saveVerdict），v1 只记 suggestion；
- 多实例调度 / 负载均衡（实例与节点显式 1:1 绑定，无路由链）。

## 2. 功能需求

### FR-01 知识库分诊解耦

- 删除 `devmind-knowledge` 的 triage 包整体（KnowledgeTriageService/Listener/Writer、
  ProposalVerdictListener/Event、ProposalCreatedEvent）与 KnowledgeController 的
  `GET /proposals/triage-status`、`POST /proposals/{id}/triage` 两端点；
- `knowledge_proposals.triage_json` 列与实体字段**保留**（历史徽标只读可见），写入路径删除；
- 前端知识库提案列表移除「AI 分诊」按钮与状态轮询，徽标组件保留只读；
- 存量 `decision_records`（capability=kb-proposal-triage）不动，CAP-56 的 records intake 血缘保留；
- SPI（DecisionEngine/DecisionRecordSink/TriageQuestions）保留在 common 供未来消费方。

### FR-02 服务实例管控

- 新实体 `classify_instances`：名称（唯一）、绑定 agent 节点（显式 1:1，无路由链）、端口、baseUrl、
  应用包引用、pythonBin、env（JSON，支持 `${PKG_DIR:<packageId>}` 占位符）、命令覆盖、状态机
  （STOPPED/STARTING/RUNNING/UNHEALTHY）、最近健康快照；
- 起停/重启/状态经 **WS `proc` 帧（协议 v15）** 下发到绑定节点执行：
  - spec 以 `argv` 数组传递（runner 直接 `ProcessBuilder(argv)` 不过 shell，跨 Windows/Linux，
    消掉 shell 转义整类问题），`command` 字符串仅作展示；
  - spec 内路径一律**相对 `<workspaceRoot>/classify/` 根**，runner 拼 workspaceRoot 并做
    **收容校验**（归一化后越界即 ack 拒绝）——服务端不感知节点路径；
  - runner 侧 pidfile + proc.json 落 `classify/run/inst-<id>/`，启动时对账进 ProcRegistry；
    **runner shutdown 不杀 managed proc**（与 sessions/exec 的 killAll 刻意的语义分岔）；
  - stop = ProcessHandle 树杀（Windows 兜底 `taskkill /F /T`）；
- 健康轮询：服务端 `@Scheduled` 对 STARTING/RUNNING/UNHEALTHY 实例打 `/healthz`
  （照 CAP-56 ServeCheckService 先例，消息脱敏后落 last_error）；start 后宽限期（默认 10min，
  GB 权重加载慢）内不判 UNHEALTHY；UNHEALTHY **不自动重启**；
- 节点协议 < v15 时操作 409，话术引导到节点页升级 runner（升级通道 CAP-21 FR-09 现成）。

### FR-03 安装包管理与分发

- 三类包：`SIDECAR_APP`（边车代码+依赖）、`MODEL_WEIGHTS`（模型权重/checkpoint）、`CORPUS`（语料/数据）；
- 新实体 `classify_packages`（唯一键 kind+name+pkg_version，sha256/sizeBytes/存储路径）与
  `classify_package_installs`（唯一键 package_id+node_id，状态机 PENDING/INSTALLED/FAILED，
  记录节点侧 installDir）；
- 上传：multipart 流式落盘（`transferTo`，禁 getBytes）→ 流式 sha256 → 原子 move；
  `spring.servlet.multipart.max-file-size` 64MB → 4GB（dist 配置样板同步）；
- 分发 = **节点拉取**（平台既有模式，不建推送通道）：服务端经 **WS `pkg` 帧（协议 v15）**
  告知 packageId/sha256/sizeBytes/installDir（相对 `classify/packages/pkg-<id>/`），
  runner 流式下载（复用 RunnerUpgrader.downloadAndVerify 的 sha 校验）→ `.tmp` 解 zip →
  原子 rename，失败清理不留半成品；GB 级下载走异步 ack 不阻塞调用线程；
- 下载端点 `GET /api/agent/classify/packages/{id}?token=` 留在 devmind-agent（持有节点 token 判定），
  内容经新 common SPI `ClassifyPackageProvider`（返回 Path，流式读出，禁 byte[]）由 devmind-classify
  供给——照 AgentLabBundleController + DecisionLabBundleProvider 先例；
- env 占位符 `${PKG_DIR:<id>}` 在实例启动组帧时展开为该包在绑定节点的 installDir
  （未安装 → 409 指明缺哪个包）；`LAYA_SLOT_MODELS={"multilingual":"${PKG_DIR:12}"}` 展开后即合法配置；
- 版本切换 = 实例改指另一已安装包 + 重启。

### FR-04 在线试分类（playground）

- 页面级诊断工具：选择目标（受管实例 / 平台默认 DECISION 端点 / 指定 DECISION 端点）→
  编辑 state（键值）与 questions（choice/score/noul + instructions + criteria）→ 运行 →
  展示逐题答案、confidence、probabilities、routing（model/reason）；
- 服务端三通道解析：instanceId 直打实例 baseUrl（本地薄封装 ClassifyPredictor，异常可上抛——
  诊断场景不适用 DecisionEngine「永不上抛」契约）；endpointId 经 ModelEndpointProvider 解析；
  缺省走 ObjectProvider&lt;DecisionEngine&gt; 默认链；
- 每次运行经 `ObjectProvider<DecisionRecordSink>` 记录（capability=`classify-playground`，
  refId=`pg-<uuid>`），sink 缺席不炸；决策记录页按 capability 过滤可见，devmind-decision 零改动；
- `GET /api/classify/playground/sample` 返回 LayaDecisionClient 样例 state/questions 供预填。

### FR-05 决策记录与实验室保留

- `devmind-decision`（HttpDecisionEngine + decision_records + 导出）与 `devmind-decision-lab`
  （评测集/评测/微调/checkpoint 登记/闸门/serve 自检）**原样保留**——playground 与未来消费方
  （工单/邮件分类）复用同一 SPI 与记录链；DECISION 端点 baseUrl 手工指向受管实例地址后，
  CAP-56 闸门与 serve 自检照常工作。

## 3. 插件化接口

| SPI（devmind-common） | 实现方 | 消费方 |
|---|---|---|
| `ClassifyPackageProvider`（新增：`packageFile(id)` 返 Path+sha+size，javadoc 写明流式禁 byte[]） | devmind-classify | devmind-agent 下载端点 |
| `AgentNodeConnector`（新增 `proc(nodeId, AgentProcCommand)` 阻塞 ack 60s、`pkgInstallAsync(nodeId, AgentPkgCommand)` 返 future） | devmind-agent | devmind-classify（ObjectProvider 探测） |
| DecisionEngine / DecisionRecordSink / ModelEndpointProvider（既有，不动契约） | devmind-decision / devmind-model | devmind-classify playground |

## 4. 依赖关系

- CAP-21（agent 节点 WS 通道、runner 自升级链）、CAP-34（服务端零执行）、CAP-36（exec 帧先例与
  AgentNodeRouter 协议门控）、CAP-48（DECISION 端点）、CAP-55（决策 SPI 与记录）、
  CAP-56（健康自检先例、checkpoint 登记消费 installDir）；
- 新模块 `devmind-classify` 依赖 common+execution（与 decision-lab 同口径，**禁依赖 agent 实现**）；
- WS 协议 v15：`AgentProtocol.CURRENT=15` + `PROC_FRAMES=15`/`PKG_FRAMES=15` 门控常量；
  老 runner 不认识新帧会静默丢 → 必须 supports() 门控 409 引导升级；帧字段组帧点 put 齐全并由
  测试钉死（红线）。

## 5. 数据模型

- `classify_instances`：id / name(128,unique) / agent_node_id / port / base_url(512) /
  app_package_id(null) / python_bin(256, @ColumnDefault("'venv/bin/python'")) / env_json(LONGVARCHAR) /
  command_override(1024,null) / status(16, @ColumnDefault("'STOPPED'")) / last_health_at /
  last_health_json(LONGVARCHAR) / last_error(1024) / created_by / created_at / updated_at；
- `classify_packages`：id / kind(16) / name(128) / pkg_version(128) / sha256(64) / size_bytes /
  original_filename(512) / stored_path(512) / uploaded_by / uploaded_at，唯一键 (kind,name,pkg_version)；
- `classify_package_installs`：id / package_id / node_id / install_dir(512) /
  status(16, @ColumnDefault("'PENDING'")) / request_id(64) / error(1024) / created_at / updated_at，
  唯一键 (package_id,node_id)。

（ddl-auto=update 自动演进；避开 H2 保留字，版本列叫 `pkg_version`；JSON 列 LONGVARCHAR。）

## 6. API 概要

- `/api/classify/instances`：CRUD + `POST /{id}/start|stop|restart` + `GET /{id}/status` +
  `GET /{id}/health`（常驻槽位/设备/sources 视图）；
- `/api/classify/packages`：列表/上传（multipart）/删除 + `POST /{id}/install`（202）；
- `/api/classify/installs`：按 packageId/nodeId 查 + `POST /{id}/retry`；
- `/api/classify/playground/run`（POST）、`/api/classify/playground/sample`（GET）；
- `/api/agent/classify/packages/{id}?token=`（节点拉取，permitAll+token 判定，FileSystemResource 流式）。

## 7. 验收标准

- 知识库提案全流程无 DecisionEngine 引用（grep 为零），提案人工 adopt/reject 不受影响；
- 本机节点 + 假边车包（Python stdlib http.server 实现的 /healthz + /v1/predict stub）E2E：
  上传包 → 分发节点 → 建实例 → start → 轮询 RUNNING → playground run →
  decision_records 出现 capability=classify-playground 行 → restart → stop → STOPPED；
- 协议 < v15 节点上实例操作返回 409 且话术含升级指引；
- runner 重启后受管进程状态对账正确（pid 存活 → RUNNING）；
- runner 关闭不杀受管边车进程。

## 8. MVP 范围

FR-01 ~ FR-05 全部即 MVP（无分期）；E2E 以假边车覆盖全链路，真 laya 权重包在 140.88 的
首装走运维指南手工验证。

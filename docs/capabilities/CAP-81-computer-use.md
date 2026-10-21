# CAP-81 远程桌面与 Computer Use（节点 GUI 观测与控制）

> 能力 ID：CAP-81 ｜ 分类：Agent 域 ｜ 状态：设计定稿 ｜ 日期：2026-10-22
> 决策记录：
> 1. **截图二进制走 HTTP 中转**（复用 FileTransferStore TTL 存储），WS 帧只带元数据——
>    Spring WS 帧缓冲小、base64 大图会拖住整条节点连接（连接是单流水线，已有发送队列
>    丢弃防御），大流量一律不挤 WS 通道，与 CAP-65 文件传输同构。
> 2. **GUI 原语由「桌面会话 helper」执行**：Windows 下 runner 常以 WinSW 服务跑在
>    Session 0，`Robot` 截到黑屏、键鼠到不了用户桌面。runner 检测到非交互会话时经
>    计划任务以登录用户身份拉起同 jar 的 `--desktop-helper` 用户态进程，helper 开
>    loopback HTTP（随机端口 + 一次性 token，写在 runner 目录），runner 仅做代理转发。
>    Linux 下 DISPLAY 可用（X11 会话内）则 runner 直做，不拉 helper；Wayland 不支持。
> 3. **LLM computer-use 循环在服务端编排**，不进 runner、也不复用 CAP-80 types
>    executor——循环状态（步序列/模型上下文/人工接管）是平台数据，runner 保持瘦执行体；
>    每步就是一次普通 computer_req 下发。
> 4. **缺省关闭 + 只读档 + 全量审计**：GUI 控制等同坐在被控机键盘前，runner 配置
>    `computerUseEnabled=false` 缺省关，另有 `computerUseReadOnly` 只截屏档；
>    服务端节点级开关双保险，动作类逐条落 agent_audit_logs。

## 1. 目的

runner 现有原语覆盖「命令行世界」（exec/terminal/file/proc），覆盖不了「图形界面世界」：
被控机上跑着的 GUI 程序（ERP 客户端、浏览器内部系统、无 CLI 的工具）平台完全够不着。
本能力给 runner 补上 GUI 原语，让服务端能**看多台电脑的屏幕、投送键鼠**，并在此之上
叠加 **LLM computer-use 循环**（截屏 → 模型决策 → 动作 → 再截屏），把「控制多台电脑」
从人工远程桌面升级为可被模型驱动的自动化能力。

两条使用线共用同一组 runner 原语与同一套安全闸门：

- **人控远程桌面**（M1/M2）：节点页内嵌实时画面 + 键鼠映射，替代 UU 远程等外部工具
  做轻量查看与操作（审计可追溯是其相对外部工具的核心增量）。
- **LLM 自动化**（M3）：给一个节点 + 一段任务描述，模型循环操作 GUI 直至完成/失败/
  步数耗尽，全程可视化、可人工接管。

## 2. 功能需求

### M1 截屏与只读观看

- **FR-01 帧协议 `computer_req`/`computer_ack`**：动作集 v1 = `screen_info`、`screenshot`。
  服务端 `AgentConnectionRegistry.computer(nodeId, action, params)` 下发等 ack
  （照 terminal/file 同款 Future 模式；**严禁在 WS 事件线程内同步等 ack**——WS 消息
  串行派发，事件链内同步等待必 15s 自死锁，历史事故）；节点离线抛 409，与现有语义一致。
  帧新字段必须三处同步：下发处 put + ack 解析 + 测试断言（launch 帧三次静默丢字段事故）。
- **FR-02 runner `ComputerUseHandler`**：`screen_info` 经 `GraphicsEnvironment` 枚举多屏
  （index/分辨率/主屏标记）；`screenshot` 用 `java.awt.Robot` 截指定屏 → JPEG，
  参数 `quality`（默认 0.7）、`scale`（默认 1.0，LLM 循环常用 0.5 降 token）、
  `screenIndex`。`GraphicsEnvironment.isHeadless()` 或 Robot 构造失败 → ack error=
  `NO_DESKTOP`（不静默黑图）。
- **FR-03 桌面会话 helper（Windows Session 0 解决方案）**：runner 启动时探测
  是否在交互式桌面会话（Windows 查 `WTSGetActiveConsoleSessionId` 等价物 /
  试触 Robot；Linux 看 `DISPLAY` 非空且能连）。非交互会话且 `computerUseEnabled=true`
  → 经计划任务（`schtasks /create /run`，任务名 `devmind-desktop-helper`，配置档
  `desktopHelperUser` 指定登录用户）拉起 `java -jar runner.jar --desktop-helper`：
  helper 开 127.0.0.1 随机端口 HTTP，端口与一次性 token 写入 `<runnerDir>/helper/
  helper.port|helper.token`（仅属主可读）；runner 每 5s 探测该文件，发现即代理转发
  全部 computer 动作，helper 退出/失联 → ack error=`HELPER_DOWN`。Linux DISPLAY
  可用时 runner 直做不拉 helper。helper 与用户桌面同生命周期（注销即死），runner
  侧心跳式重拉（节流 60s）。
- **FR-04 截图传输**：helper/runner 截屏后 POST `/api/agent/computer/upload`
  （runner 既有 token 鉴权，复用 FileTransferStore TTL 存储，TTL 10min），
  `computer_ack` 只带 `{fileToken, width, height, screenIndex, capturedAt}`；
  前端/编排器经 `GET /api/agent/computer/file/{fileToken}` 拉取。**截图永不落业务库**。
- **FR-05 能力广告**：hello 帧 `capabilities` CSV 加 `computer`（runner 配置启用且
  直做/helper 至少一路可用才广告）；落库节点 capabilities 列，节点页可展示
  「远程桌面可用/只读/不可用」徽标。协议版本 +1。
- **FR-06 服务端 REST**：`GET /api/agent/nodes/{id}/computer/screen-info`、
  `POST /api/agent/nodes/{id}/computer/screenshot {quality?,scale?,screenIndex?}`
  → `{fileToken,...}`。节点离线 409、未广告能力 409（`COMPUTER_UNAVAILABLE`）、
  服务端节点开关关闭 403。
- **FR-07 前端「远程桌面」页签**：节点详情页新增页签（嵌面板式，与 CAP-80 之后
  「文件浏览」页签同款嵌法，nodeId 写回 URL）：画面区按 1fps 轮询截图（0.5/1/2fps
  可调 + 暂停），多屏切换下拉，M1 全程只读（鼠标悬停不投送任何事件）。

### M2 交互式键鼠控制

- **FR-08 鼠标动作**：`mouse_move{x,y}` / `mouse_click{x,y,button=left|middle|right,
  clicks=1|2}` / `mouse_drag{from,to}` / `wheel{x,y,deltaY}`。坐标 = 原始屏幕像素。
- **FR-09 键盘动作**：`key_press{key,modifiers[]}`（组合键，key 用统一键名表
  A-Z/0-9/F1-F12/ENTER/ESC/…，runner 映射 `KeyEvent` 键码，未知键名 ack error=
  `BAD_KEY`）；`type_text{text}`——纯 ASCII 走逐键，含非 ASCII（中文）走
  `clipboard_set` + Ctrl+V 粘贴（Robot 打不了 Unicode），粘贴后恢复原剪贴板内容。
- **FR-10 剪贴板双向**：`clipboard_get`（文本，上限 64KB）/ `clipboard_set{text}`。
- **FR-11 前端交互态**：观看画布点击/移动/滚轮 → 按 scale 与画布偏移换算回原始
  分辨率坐标投送；键盘捕获开关（开启时画布聚焦拦截按键，ESC 退出）；常用组合键
  按钮条（Ctrl+C/V、Alt+Tab、Win+E 等）。**Ctrl+Alt+Del 不支持**（安全注意序列只能
  由系统产生），UI 明示。
- **FR-12 只读双保险**：runner `computerUseReadOnly=true` 时动作类 action 一律 ack
  error=`READ_ONLY`；服务端节点详情「远程桌面」开关分档：关闭/只读/可控制，
  服务端在 REST 入口先挡（403）， Runner 档是底线。

### M3 LLM Computer-Use 循环

- **FR-13 任务实体与编排**：`computer_use_tasks`（id/nodeId/prompt/status=
  RUNNING|DONE|FAILED|STOPPED|TAKEN_OVER/maxSteps/maxMinutes/stepCount/模型配置引用/
  创建人/起止时间）。`ComputerUseTaskService` 服务端编排循环：截屏（scale 0.5）
  → 送「模型接入」配置的视觉模型（历史 N 步摘要 + 当前截图）→ 解析动作 JSON →
  下发 computer_req → 等 ack → 下一步；命中 maxSteps/maxMinutes 任一即 FAILED
  （原因可读）。
- **FR-14 动作协议**：模型输出严格 JSON：`{"action":"left_click|type|key|scroll|
  wait|done|fail","coordinate":[x,y],"text"?,"key"?,"reason":"…"}`，坐标系 = **缩放后
  截图像素**（与模型所见一致），服务端按 scale 换算回原始分辨率再下发；
  解析失败/越界坐标计一次可重试错误（连续 3 次 FAILED）。`reason` 必填落库，
  过程可审计。
- **FR-15 过程可视与接管**：任务详情页步序列（缩略图 + 动作 + reason + 耗时），
  WS 推送追加；「人工接管」按钮把任务置 TAKEN_OVER 并跳转该节点远程桌面交互态
  （循环停、画面不断）；「停止」随时可按。
- **FR-16 试运行档**：任务可标 `dryRun=true`——循环照跑、模型动作只记录不下发，
  用于评估提示词与模型可靠性（接 CAP-55/56 决策记录导出口径预留）。

### M4 审计与运维

- **FR-17 全量审计**：动作类（鼠标/键盘/剪贴板写）逐条落 `agent_audit_logs`
  （actor/nodeId/action/参数脱敏——type_text 与 clipboard_set 只记长度不记内容）；
  截图不逐条（高频），按「观看会话」记一条（谁、哪节点、起止、帧数）。
- **FR-18 限频**：动作帧每节点 ≤20/s（超出 ack error=`RATE_LIMITED`）；
  截图轮询服务端强制最小间隔 300ms；LLM 循环每节点同时只许 1 个 RUNNING 任务
  （唯一约束 + 友好 409）。
- **FR-19 运维视图**：管理页「远程桌面会话」列表——当前谁在看/在控哪台（含 LLM
  任务），admin 可强制断开；节点页能力徽标与 helper 状态（直做/helper/不可用）。

## 3. 否决方案

- **接 VNC/RDP 协议（被控端装 VNC server）**：多一套被控端部署与凭证体系，runner
  已在场且 `Robot` 零依赖；VNC 的增量（压缩/增量帧）在 1-2fps JPEG 轮询的场景下
  不值这个复杂度。后续若需高帧率再评估（协议不变，传输换实现）。
- **WS 帧内嵌 base64 截图**：见决策 1，大流量不挤 WS。
- **LLM 循环放 runner 侧**：runner 瘦 jar 无模型接入、无任务状态；编排是平台职责。
- **Windows 服务内 CreateProcessAsUser 跨会话直接起 GUI 线程**：会话句柄/权限/杀软
  三重敏感，计划任务拉起独立用户态 helper 进程更可解释、可独立重启。
- **复用 CAP-80 types executor 跑 computer-use**：types 任务是「runner 上跑一段命令」，
  computer-use 是「服务端编排的模型循环」，语义与状态归属都不同。

## 4. 架构与模块归属

| 层 | 归属 | 内容 |
|---|---|---|
| runner | devmind-agent-runner | `ComputerUseHandler`（直做）+ `--desktop-helper` 入口与 loopback server + helper 探测/拉起/代理 |
| 服务端 | devmind-agent | registry.computer() 下发/ack、`ComputerController`（REST）、截图 upload/file 端点（复用 FileTransferStore）、`ComputerUseTaskService`（M3）、节点开关字段 |
| 前端 | features/agent（节点详情「远程桌面」页签）+ M3 任务页 | 观看画布、键鼠投送、任务步序列 |
| 审计 | devmind-agent | 复用 agent_audit_logs |

依赖方向：agent →（WS）→ runner；agent 复用 devmind-execution 无必要（不走
StepRunner——computer 动作是同步短请求，非日志流任务）。common 不动。

## 5. 数据模型

- `agent_nodes` 加列：`computer_use_mode` VARCHAR(16) NULL（OFF/READONLY/CONTROL，
  NULL=跟随 runner 广告的只读上限）；capabilities 列沿用 FR-05。
- M3 新表 `computer_use_tasks`：id CHAR(26) PK / node_id BIGINT / prompt
  LONGVARCHAR / status / dry_run BIT / max_steps INT / max_minutes INT /
  step_count INT / model_config_id / taken_over_by / created_by / created_at /
  finished_at / fail_reason VARCHAR(500)。
- M3 新表 `computer_use_steps`：id PK / task_id FK / step_no INT / action /
  coordinate / reason VARCHAR(1000) / ack_error / duration_ms / screenshot_token
  （TTL 存储引用，过期即空）。**禁 H2 保留字列名**；文本列带 `@JdbcTypeCode`。
- 截图与 helper 元数据不入库（TTL 存储 / runner 本地文件）。

## 6. API 概要

REST（均挂 `/api/agent`，admin 或 `agent:computer` 权限点）：

- `GET /nodes/{id}/computer/screen-info`
- `POST /nodes/{id}/computer/screenshot` / `POST /nodes/{id}/computer/action`
- `GET /computer/file/{fileToken}`（截图拉取）
- `POST /nodes/{id}/computer/upload`（runner 回调，runner token 鉴权）
- M3：`POST /computer-tasks`、`GET /computer-tasks`、`GET /computer-tasks/{id}`
  （含 steps）、`POST /computer-tasks/{id}/stop|takeover`

WS 帧：`computer_req {reqId, action, params…}` → `computer_ack {reqId, ok,
error?, fileToken?, width?, height?, …}`。

## 7. 安全

- runner `computerUseEnabled` 缺省 false、`computerUseReadOnly` 提供只截屏档；
  服务端节点三级开关（OFF/READONLY/CONTROL）REST 入口先挡。
- 动作逐条审计且内容脱敏（FR-17）；helper loopback 一次性 token 仅属主可读。
- 限频（FR-18）；`type_text` 单次 ≤2000 字符；截图 TTL 10min 自动清。
- LLM 循环单节点独占 + dryRun 档 + 随时停止/接管（FR-15/16/18）。
- 明确不覆盖：UAC 安全桌面、Ctrl+Alt+Del、锁屏界面（截到锁屏或 NO_DESKTOP，
  文档说明而非绕过）。

## 8. 里程碑

| 里程碑 | 内容 | 对应 FR |
|---|---|---|
| M1 | 截屏 + helper + 只读观看 | FR-01~07 |
| M2 | 交互键鼠 + 剪贴板 | FR-08~12 |
| M3 | LLM 循环 + 接管 | FR-13~16 |
| M4 | 审计/限频/运维视图 | FR-17~19 |

## 9. 验收标准

- M1：Windows 服务态 runner + 已登录桌面 → 节点页看到实时画面（非黑屏）；
  Linux X11 runner 直做同样可见；未启用节点 409、无桌面环境 ack NO_DESKTOP；
  helper 被杀后 60s 内自愈。
- M2：画布点击/滚轮/键盘中英文输入在被控机生效且坐标无偏移（多屏各自验证）；
  只读档动作被两端拒绝并有审计记录。
- M3：给「打开记事本输入指定文本」任务，循环 ≤10 步完成 DONE，步序列含截图
  与 reason；maxSteps 耗尽 FAILED 原因可读；接管后循环停、画面可操作。
- M4：动作审计逐条可查且不含明文键入内容；限频触发 RATE_LIMITED；
  运维页可强制断开观看会话。
- 通用：`mvn -q test` 绿、`npx tsc -b` 绿；E2E（tests/ 沉淀）：隔离 app +
  真 runner 直做模式（Xvfb/虚拟显示或 headless 桩 executor）跑 FR-01/02/04/06
  全链；E2E 后清理自起进程。

## 10. 风险与开放问题

- **锁屏/注销**：锁屏时截到锁屏界面、键鼠无效；注销后 helper 死亡。定位=「办公
  时段辅助工具」，不做无人值守锁屏穿透（也不应做）。开放问题：是否检测锁屏并
  在 UI 明示？
- **Wayland**：X11 only，Wayland 桌面 ack NO_DESKTOP。若未来主力桌面转 Wayland
  需另起实现（portal API）。
- **UAC/安全桌面**：提权弹窗出现时画面冻结/黑屏，属 Windows 安全设计，文档说明。
- **多显示器坐标系**：screenIndex 各自独立坐标系，前端切换屏即换坐标原点；
  跨屏拖拽不支持。
- **LLM 误操作**：模型点错/删东西——缓解靠 dryRun、只读任务白名单（待定：
  M3 是否加「禁键名单/禁点区域」）、审计可追责、人工接管。上线初期建议仅对
  非生产办公机开放 CONTROL 档。
- **helper 拉起依赖计划任务权限**：runner 服务账号需能 `schtasks` 到目标用户
  会话；域策略收紧的环境可能失败 → helper 拉起失败要可读报错并给手工拉起指引
  （双击 helper 快捷方式兜底）。

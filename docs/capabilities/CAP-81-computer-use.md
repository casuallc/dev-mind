# CAP-81 远程桌面（节点 GUI 观测与控制）

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
> 3. **缺省关闭 + 只读档 + 全量审计**：GUI 控制等同坐在被控机键盘前，runner 配置
>    `computerUseEnabled=false` 缺省关，另有 `computerUseReadOnly` 只截屏档；
>    服务端节点级开关双保险，动作类逐条落 agent_audit_logs。
> 4. **computer REST 全端点 ADMIN**：平台是角色制 RBAC（`hasRole("ADMIN")` /
>    `hasAnyRole`），没有操作级「权限点」机制；computer 读写（含截图拉取 GET）同
>    CAP-65 文件浏览一律 `hasRole("ADMIN")`，不新造 `agent:computer` 权限点。
> 5. **观看会话需服务端状态载体**：computer 帧协议是 stateless 请求/响应，而「谁在
>    看/在控哪台 + 强制断开 + 观看审计」需要一个平台侧活跃视图清单
>    （`computer_view_sessions` 表 + 撤销即失效），否则 FR-13/15 没有实现抓手。
>
> 相关能力：模型驱动的 GUI 自动化（LLM computer-use 循环）已拆为独立能力
> [CAP-82](CAP-82-llm-computer-use.md)，复用本能力交付的 computer 原语。

## 1. 目的

runner 现有原语覆盖「命令行世界」（exec/terminal/file/proc），覆盖不了「图形界面世界」：
被控机上跑着的 GUI 程序（ERP 客户端、浏览器内部系统、无 CLI 的工具）平台完全够不着。
本能力给 runner 补上 GUI 原语，让服务端能**看多台电脑的屏幕、投送键鼠**，把人控远程
桌面收进平台——审计可追溯是其相对 UU 远程等外部工具的核心增量。在此之上由模型驱动的
GUI 自动化（LLM computer-use 循环）拆为独立能力 CAP-82，本能力交付的 computer 原语
即其动作底座。

## 2. 功能需求

### M1 截屏与只读观看

- **FR-01 帧协议 `computer_req`/`computer_ack`**：动作集 v1 = `screen_info`、`screenshot`
  （M2 起扩 mouse/keyboard/clipboard）。服务端 `AgentConnectionRegistry.computer(nodeId,
  action, params)` 下发等 ack（照 terminal/file 同款 Future 模式；**严禁在 WS 事件线程内
  同步等 ack**——WS 消息串行派发，事件链内同步等待必 15s 自死锁，历史事故）；节点离线
  抛 409，与现有语义一致。协议门控 `AgentProtocol.COMPUTER_FRAMES = 24`：computer 帧属
  「必须认识」（老 runner 静默忽略会让 REST 空等超时），服务端 `supports()` 门控，协议
  <v24 抛 409 引导升级（照 FILE_FRAMES 先例）。帧新字段必须三处同步：下发处 put + ack
  解析 + 测试断言（launch 帧三次静默丢字段事故）。
- **FR-02 runner `ComputerUseHandler`**：`screen_info` 经 `GraphicsEnvironment` 枚举多屏
  （index/分辨率/主屏标记）；`screenshot` 用 `java.awt.Robot` 截指定屏 → JPEG，
  参数 `quality`（默认 0.7）、`scale`（默认 1.0）、`screenIndex`。
  `GraphicsEnvironment.isHeadless()` 或 Robot 构造失败 → ack error=`NO_DESKTOP`
  （不静默黑图）。
- **FR-03 桌面会话 helper（Windows Session 0 解决方案）**：runner 启动时探测
  是否在交互式桌面会话（Windows 查 `WTSGetActiveConsoleSessionId` 等价物 /
  试触 Robot；Linux 看 `DISPLAY` 非空且能连）。非交互会话且 `computerUseEnabled=true`
  → 经计划任务（`schtasks /create /run /it`，任务名 `devmind-desktop-helper`，
  `/it` 交互令牌模式免存用户密码、仅在目标用户已登录时生效——恰与 helper 与用户桌面
  同生命周期吻合；配置档 `desktopHelperUser` 指定登录用户）拉起
  `java -jar runner.jar --desktop-helper`：helper 开 127.0.0.1 随机端口 HTTP，端口与
  一次性 token 写入 `<runnerDir>/helper/helper.port|helper.token`（仅属主可读）；runner
  每 5s 探测该文件，发现即代理转发全部 computer 动作，helper 退出/失联 → ack error=
  `HELPER_DOWN`。Linux DISPLAY 可用时 runner 直做不拉 helper。**helper/直做进程一律
  DPI-aware 启动**（`-Dsun.java2d.dpiaware=true`）——Windows 125%/150% 缩放下非
  DPI-aware 的 JVM 会把 `GraphicsEnvironment` 逻辑坐标当物理像素，键鼠坐标系统性偏移
  （M2 验收「坐标无偏移」的翻车点）。helper 与用户桌面同生命周期（注销即死），runner
  侧心跳式重拉（节流 60s）。
- **FR-04 截图传输**：helper/runner 截屏后 POST `/api/agent/computer/upload`
  （runner 既有 token 鉴权，复用 FileTransferStore TTL 存储，TTL 10min），
  `computer_ack` 只带 `{fileToken, width, height, screenIndex, capturedAt}`；
  前端经 `GET /api/agent/computer/file/{fileToken}` 拉取。**截图永不落业务库**。
- **FR-05 能力广告**：hello 帧 `capabilities` CSV 加 `computer`（runner 配置启用且
  直做/helper 至少一路可用才广告）；落库节点 capabilities 列，节点页可展示
  「远程桌面可用/只读/不可用」徽标。协议版本 +1（v24，门控常量
  `AgentProtocol.COMPUTER_FRAMES`，见 FR-01）。
- **FR-06 服务端 REST**：`GET /api/agent/nodes/{id}/computer/screen-info`、
  `POST /api/agent/nodes/{id}/computer/screenshot {quality?,scale?,screenIndex?}`
  → `{fileToken,...}`。节点离线 409、未广告能力 409（`COMPUTER_UNAVAILABLE`）、
  服务端节点开关关闭 403。
- **FR-07 前端「远程桌面」页签**：节点详情页新增页签（嵌面板式，与 CAP-80 之后
  「文件浏览」页签同款嵌法，nodeId 写回 URL）：进入先开观看会话（`viewSessionId`
  随后续截图轮询携带，供 FR-13 审计与 FR-15 强制断开），画面区按 1fps 轮询截图
  （0.5/1/2fps 可调 + 暂停），多屏切换下拉，M1 全程只读（鼠标悬停不投送任何事件）。

### M2 交互式键鼠控制

- **FR-08 鼠标动作**：`mouse_move{x,y}` / `mouse_click{x,y,button=left|middle|right,
  clicks=1|2}` / `mouse_drag{from,to}` / `wheel{x,y,deltaY}`。坐标 = 原始屏幕物理
  像素（DPI-aware 保证，见 FR-03）。
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

### M3 审计与运维

- **FR-13 全量审计**：动作类（鼠标/键盘/剪贴板写）逐条落 `agent_audit_logs`，新增
  category `COMPUTER`（九分类外第十类）：action→`event` 列（MOUSE_CLICK/KEY_PRESS/
  CLIPBOARD_SET/…）、脱敏参数→`detail` 列、操作人→`actor` 列（type_text 与
  clipboard_set 只记长度不记内容）；截图不逐条（高频），按「观看会话」记一条
  （谁、哪节点、起止、帧数），观看会话实体见第 5 节 `computer_view_sessions`。
- **FR-14 限频**：动作帧每节点 ≤20/s（超出 ack error=`RATE_LIMITED`）；
  截图轮询服务端强制最小间隔 300ms。
- **FR-15 运维视图**：管理页「远程桌面会话」列表——当前谁在看/在控哪台，
  admin 可强制断开；节点页能力徽标与 helper 状态（直做/helper/不可用）。

## 3. 否决方案

- **接 VNC/RDP 协议（被控端装 VNC server）**：多一套被控端部署与凭证体系，runner
  已在场且 `Robot` 零依赖；VNC 的增量（压缩/增量帧）在 1-2fps JPEG 轮询的场景下
  不值这个复杂度。后续若需高帧率再评估（协议不变，传输换实现）。
- **WS 帧内嵌 base64 截图**：见决策 1，大流量不挤 WS。
- **Windows 服务内 CreateProcessAsUser 跨会话直接起 GUI 线程**：会话句柄/权限/杀软
  三重敏感，计划任务拉起独立用户态 helper 进程更可解释、可独立重启。

## 4. 架构与模块归属

| 层 | 归属 | 内容 |
|---|---|---|
| runner | devmind-agent-runner | `ComputerUseHandler`（直做）+ `--desktop-helper` 入口与 loopback server + helper 探测/拉起/代理 |
| 服务端 | devmind-agent | registry.computer() 下发/ack、`ComputerController`（REST）、截图 upload/file 端点（复用 FileTransferStore）、观看会话管理、节点开关字段 |
| 前端 | features/agent（节点详情「远程桌面」页签） | 观看画布、键鼠投送 |
| 审计 | devmind-agent | 复用 agent_audit_logs |

依赖方向：agent →（WS）→ runner；agent 复用 devmind-execution 无必要（不走
StepRunner——computer 动作是同步短请求，非日志流任务）。common 不动。

## 5. 数据模型

- `agent_nodes` 加列：`computer_use_mode` VARCHAR(16) NULL（OFF/READONLY/CONTROL，
  NULL=跟随 runner 广告的只读上限）；capabilities 列沿用 FR-05。
- 观看会话表 `computer_view_sessions`：id CHAR(26) PK / node_id BIGINT /
  user_id / mode(WATCH|CONTROL) / started_at / ended_at / frame_count INT /
  revoked_by / revoked_at。活跃视图清单权威在此（服务端另有内存活跃集合供快读）；
  「强制断开」= 更新 revoked_at 并使内存活跃集合失效，该 node 后续截图/动作 REST
  入口即 403，轮询式观看自然终止（FR-15 的实现抓手）。
- 审计：`AgentAuditLogEntity` 增 `CAT_COMPUTER` 分类常量（动作类 GUI 审计，FR-13）。
- 截图与 helper 元数据不入库（TTL 存储 / runner 本地文件）。

## 6. API 概要

REST（均挂 `/api/agent`，**全端点 `hasRole("ADMIN")`**——平台为角色制 RBAC，无操作级
权限点；computer 读写含截图拉取 GET 同 CAP-65 文件浏览，须在 SecurityConfig 通用
`GET /api/**` 规则前加行；runner 回调端点 permitAll + 控制器内 token 判定）：

- `GET /nodes/{id}/computer/screen-info`
- `POST /nodes/{id}/computer/screenshot` / `POST /nodes/{id}/computer/action`
- `POST /nodes/{id}/computer/view-sessions`（开观看会话，返回 viewSessionId）
- `GET /computer/file/{fileToken}`（截图拉取，须带有效 viewSessionId）
- `POST /computer/view-sessions/{id}/revoke`（admin 强制断开，FR-15）
- `POST /nodes/{id}/computer/upload`（runner 回调，runner token 鉴权）

WS 帧：`computer_req {reqId, action, params…}` → `computer_ack {reqId, ok,
error?, fileToken?, width?, height?, …}`。

## 7. 安全

- runner `computerUseEnabled` 缺省 false、`computerUseReadOnly` 提供只截屏档；
  服务端节点三级开关（OFF/READONLY/CONTROL）REST 入口先挡。
- 动作逐条审计且内容脱敏（FR-13）；helper loopback 一次性 token 仅属主可读。
- helper 计划任务走 `/it` 交互令牌（免存用户密码）；helper/直做进程 DPI-aware 启动。
- 限频（FR-14）；`type_text` 单次 ≤2000 字符；截图 TTL 10min 自动清。
- 观看会话可被 admin 强制断开（撤销即 403，见 `computer_view_sessions`）。
- 明确不覆盖：UAC 安全桌面、Ctrl+Alt+Del、锁屏界面（截到锁屏或 NO_DESKTOP，
  文档说明而非绕过）。

## 8. 里程碑

| 里程碑 | 内容 | 对应 FR |
|---|---|---|
| M1 | 截屏 + helper + 只读观看 | FR-01~07 |
| M2 | 交互键鼠 + 剪贴板 | FR-08~12 |
| M3 | 审计/限频/运维视图 | FR-13~15 |

## 9. 验收标准

- M1：Windows 服务态 runner + 已登录桌面 → 节点页看到实时画面（非黑屏）；
  Linux X11 runner 直做同样可见；未启用节点 409、无桌面环境 ack NO_DESKTOP；
  helper 被杀后 60s 内自愈。
- M2：画布点击/滚轮/键盘中英文输入在被控机生效且坐标无偏移（多屏各自验证）；
  只读档动作被两端拒绝并有审计记录。
- M3：动作审计逐条可查且不含明文键入内容；限频触发 RATE_LIMITED；
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
- **helper 拉起依赖计划任务权限**：runner 服务账号需能 `schtasks /it` 到目标用户
  交互会话（/it 免存密码，但要求该用户已登录）；域策略收紧的环境可能失败 →
  helper 拉起失败要可读报错并给手工拉起指引（双击 helper 快捷方式兜底）。
- **Windows DPI 缩放**：125%/150% 缩放下非 DPI-aware 会键鼠坐标偏移；helper/直做
  进程强制 DPI-aware（FR-03），多屏各自验证保留在 M2 验收。

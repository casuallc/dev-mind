# CAP-50 CLI 会话流式输出（runner 会话逐字打字机）

> 能力 ID：CAP-50 ｜ 分类：底座 ｜ 状态：已实现 ｜ 日期：2026-09-21
> 缘起：CAP-49 让通用问答有了逐字打字机，而同样是 claude 驱动的 runner 会话（CAP-05/21/34）正文仍是
> 「一整段蹦出来」——**同一个页面组件**（`shared/chat/ChatStream.tsx`）、**同一条 WS 链路**
> （`ChatPanel` 被 `SessionsBoard` 与 `ChatsBoard` 共用），唯独 CLI 执行体没有流式。
> 根因不是缺什么大件：`CliProcessLauncher.buildCommand()` 从没传 `--include-partial-messages`，
> 于是 `CliEventParser` 里**早已写好**的 `stream_event → text_delta` 分支成了死代码，一行都没触发过。

## 1. 目的

| 执行体 | 现状 | 本能力后 |
|---|---|---|
| 模型执行体（CAP-49） | 逐 token 流式（`text_delta` 节流推送） | 不变 |
| CLI 执行体（runner 会话） | 一回合一条完整 `assistant` 落地才显示 | **与模型执行体一致**：增量打底、全量覆盖收口 |

**硬约束：前端零改动**。这不仅是为了省事，也是可行性的证据——渲染层已经准备好了
（`ChatStream.tsx` 的 `text_delta` 累积、空增量保护、"全量 `assistant` 到达即覆盖流式气泡"三条逻辑都在，
其注释还明说「CLI 未开 partial messages ⇒ 恒无此事件」）。本次只把这条路径**点亮并加固到能承住 15 倍帧率**。

## 2. 功能需求

### FR-01 partial 帧采集与解析层白名单

- `CliProcessLauncher.buildCommand()` 增 `--include-partial-messages`（前置条件 `-p` +
  `--output-format stream-json` 本就在命令里，与 `--input-format stream-json`、`--resume` 无冲突）。
- `CliEventParser` 的 `stream_event` 分支由内联 case 提炼为 `parseStreamEvent`，并收紧为**命名空间级白名单**：
  只有 `event.type == content_block_delta` **且** `event.delta.type == text_delta` **且**
  `parent_tool_use_id` 为空时产一条 `text_delta`；**其余一律静默丢弃**，不降级成 `log`。
- 该 flag 下 claude **仍会照常吐完整 `assistant`**（CLI 自身行为），这是"增量打底、全量收口"成立的前提。
- 产生的 `text_delta` **不带 payload**：CAP-49 的等价事件就是无 payload 的，而 payload 会按 LONGTEXT 逐行落库。

### FR-02 增量聚合（节流 + 保序收口）

- 新增 `RunnerDeltaAggregator`（devmind-common），每会话一个实例，口径对齐 CAP-49：
  **24 字符 / 120ms** 触发 flush，另设 `maxBufferChars`（100KB）防病态突刺。
- **只丢弃真正为空的增量**：仅含空白的增量在 token 流里是真实内容（词间空格），丢掉会破坏
  「增量拼接 == 全量正文」。
- **聚合器必须是每会话字段，不能挂在 parser 上**：`CliEventParser` 全 runner 只有一个实例
  （所有会话共用一个），挂上去就是跨会话污染 + 数据竞争。
- **顺序保证**：runner 不上行 seq，服务端在到达时 `nextSeq()`——唯一可用的排序原语就是 `send()` 的调用顺序。
  因此聚合器「追加/按阈值 flush」与「非增量事件前强制 flush」必须在**同一把锁内**完成：判定在锁内、
  控制在锁内，否则同会话的另一条读取线程（stdout/stderr 各一条）会插进来把正文放错位置。
- **收口时机**：`onProcessEnd` 第一行强制 flush——必须在 `waitFor()`、产出回传、finalizer（最长可阻塞 30s）
  之前，且必在 `exit` 帧之前。kill 与 stdin EOF 两条退出路径最终都走这里。不额外起定时器：
  CLI 每 token 一帧天然驱动阈值判定，紧随其后的完整 `assistant`（非增量）还会兜底 flush。

### FR-03 出口串行化（修既有静默丢帧）

**这是既有的 bug，被本能力放大**：`ServerConnection.send` 直接调 JDK
`WebSocket.sendText(...).exceptionally(...)`，而 JDK 在上一次发送未完成时返回
`failedFuture(new IllegalStateException("Send pending"))`——`.exceptionally` 只记一条 WARN，**帧就这么没了**。

- 改法：**单写者队列 + 专用发送线程**（`SEND_QUEUE_CAPACITY=10_000`、`SEND_TIMEOUT_MS=10s`）。
  `sendAndWait` 走同一队列并把「真正落线」作为 `done` 的完成条件；连接更替（重连）后，旧连接上排队的帧
  按「断线期间上行帧丢弃」语义丢弃。
- 为什么优先队列而不是"发送锁 + 链式等待 future"：队列顺带把同会话相邻帧做最廉价的一次合并，
  且把"发送"从生产者线程（读循环）里彻底剥离。
- 后果量化：丢 `text_delta` 会被全量 `assistant` 自愈，但丢 `tool_use`/`result`/**`exit`** 不会——
  **丢一个 exit 帧 = 会话永久卡在 RUNNING**（服务端唯一兜底是重连时的 hello 对账）。

### FR-04 回放与推送抗压

**① 环形缓冲排除 `text_delta`**（`AbstractSessionRuntime.publish` 对增量只广播 + 落库，不进 ring）。

理由不是洁癖：`SessionWsHandler.snapshot` 的回放**只有环形缓冲**，而前端只在该会话已不活跃时才走 REST
补拉历史——**会话进行中刷新页面，环形缓冲就是唯一历史来源**。默认 1000 条，一条 4000 字回答按 24 字节流
也有一百多条 delta，一挤就把工具卡片、历史提问、前面的话题整片冲掉，用户满屏碎片。
排除后回放退回「气泡等全量 `assistant` 到达时成形」的既有行为；DB 里增量仍在（见 §3）。

**② `/ws/sessions/{id}` 加慢客户端保护**：把 `/ws/chats` 的
`WebSocketHandlerDecorator` + `ConcurrentWebSocketSessionDecorator(10s / 512KB)` 照搬过来。
`SessionWsHandler.send` 是**在发布者线程上阻塞式 sendMessage**，而 runner 会话的发布者就是节点入站 WS 线程
——慢浏览器会一路反压到节点连接，把状态流转（`result` → `WAITING_INPUT`）一起堵在后面。
同批修正 `ChatWsConfig` 里「会话 WS 事件频率低所以不装饰」的过时注释（本能力让该前提失效）。

### FR-05 事件补拉上限

`GET /sessions/{id}/events` 原先无 limit，取全量。开流式后单会话事件数涨十几倍，而会话页每次终态切换都会
补拉一次——这条查询会变成全产品最慢的一条。

- 仓库方法由「顺序取全量」改为 `findBySessionIdAndSeqGreaterThanOrderBySeqDesc` + `Pageable`
  （与 chat 侧 CAP-49 的既有解法同一手法：倒序取页、调用方反转）。
- 接口增 `limit`（默认 `0` = 走默认上限 **2000**，超硬上限 **20000** 按硬上限截断）；不传 limit 的既有调用方
  行为不变；**返回仍是 seq 升序**，前端按 seq 追加的逻辑不受影响。

### FR-06 逃生开关

`agent.properties` 增 `partialMessages`（默认 `true`）。万一某节点上的 claude 版本不认这个 flag
（未知选项会让进程非零退出、会话直接 FAILED），改配置重启即可，不用重新打包发节点。
`RuntimeSettings` 增第 6 组件 `includePartialMessages`，并**补一个 5 参便捷构造器委托 `true`**，
使既有构造点（`defaults()`/两个 wither/各 Properties）一行都不用改。

### FR-07 假执行体补帧（回归网）

`fake-agent.js` 原先只发完整 `assistant`，这条链（解析 → 聚合 → 出口 → 落库 → 回放）在 E2E 里就是空的。
现在每条正文之前补发一片 `stream_event` 序列，并混入真实 CLI 会发但必须被吞掉的帧
（`thinking_delta` / `input_json_delta` / `signature_delta` / `message_delta` / `ping`，以及一条
`parent_tool_use_id` 非空的子 agent 增量）——它们若被误降级成 `log` 或混进主气泡，E2E 断言立刻失败。

## 3. 关键设计（已定）

- **增量落库（与 CAP-49 一致）**：CLI 被 kill 时不会补发全量 `assistant`，已 flush 的增量是那段正文的
  **唯一痕迹**；不落库则刷新后这段正文直接消失。
- **环形缓冲排除增量，但 DB 保留增量**：两者服务于不同读取路径——回放要的是"能成形的一整段"，
  审计要的是"一个字都不丢"。这是本能力最容易误读的一条，故两处各写了理由注释（FR-04 ① / FR-02）。
- **只过滤增量、不过滤全量**：`parent_tool_use_id` 非空 = 子 agent（Task）的流。全量 `assistant` 今天就不看
  这个字段（子 agent 的完整正文本来就并进主气泡），过滤增量只是不让子 agent 的 token 把主流气泡搅乱。
  改全量过滤会变成"子 agent 正文彻底不显示"，那是另一个能力的范围。
- **前端零改动**：增量打底 + 全量覆盖收口这条不变式由 `ChatStream.tsx` 既有逻辑承担，本次只补了
  「会话 WS 慢客户端装饰」一处（属于链路承压，不是渲染）。
- **不打散 CAP-49 的模型链路**：不把 `ModelSessionRuntime` 的节流逻辑与 runner 聚合器合并成一个共享类——
  那条链路已在生产验证，重构收益低、回归风险实打实。两边各自留一份是"各能力自包含"的取舍。

## 4. 插件化接口（实现定稿）

- `RuntimeSettings`（common）增第 6 组件 `includePartialMessages` + 5 参兼容构造器 + `withIncludePartialMessages`。
- 新增 `RunnerDeltaAggregator`（common `agent.runtime`，`Tuning` record + `defaults()` = 24/120/100KB）。
- `RunnerConfig` 读 `agent.properties.partialMessages`；`AgentRunnerMain` 传入 `RuntimeSettings`。
- `ServerConnection` 增出口队列与写线程（不新增对外的 SPI）。

## 5. 数据模型

**零表结构变更**。复用 `session_events`：`text_delta` 行数按 24 字/120ms 节流后约为一回合 100~200 行
（不节流是 1500~3000 行）。正因为 `text_delta` 成为主力行，才有了 FR-05 的补拉上限。

## 6. API 概要

```
GET /api/sessions/{id}/events?afterSeq=-1&limit=0   新增 limit（0=默认2000，硬上限20000）；返回仍升序
WS  /ws/sessions/{id}                               协议零变更（不新增事件类型，复用 text_delta）
runner 协议                                         零变更（text_delta 是既有事件类型）
```

## 7. 验收标准

- **增量先到、全量收口**：WS 与落库都能看到「多条 `text_delta` → 一条完整 `assistant`」，
  且**增量拼接逐字等于**该 `assistant` 正文；
- **无空增量**：改坏解析层会为每个 `stream_event` 吐一条空增量——E2E 断言"不存在空 `text_delta`"是最强的
  前后判别式；同时断言 `thinking`/子 agent/原始 `stream_event` 帧/`partial_json` 一个都没漏进事件流；
- **回放不碎**：会话进行中刷新页面（重连取 snapshot），snapshot 里**没有** `text_delta`，
  历史提问与工具卡片仍在；
- **不丢帧**：并发生产者下出口队列一帧不丢、同一生产者的相对顺序不乱（"Send pending" 撞车次数为 0）；
- **补拉有界**：`limit<=0` 落默认上限、超上限按硬上限截断、截断时保留的是**最近** N 条且仍升序；
- **零回归**：不传 limit 的既有调用方行为不变；模型执行体（CAP-49）链路一行未改；`/ws/chats` 行为不变。

**验收证据**（2026-09-21）：

- 后端 `mvn -q test` 全绿：**674 项**（本能力新增 `CliEventParserStreamEventTest`、
  `RunnerDeltaAggregatorTest`、`ServerConnectionEgressTest`、`SessionReplayRingTest`、`SessionEventsLimitTest`
  与 `CliProcessLauncherBuildCommandTest` 的 flag 注入用例）；
- E2E `tests/e2e-agent-node.py`（runner 用 `executor=fake` 真跑）：
  `[7.5]` 新增 `tests/cap50-sessions-ws.mjs` 断言 3 条增量 → 67 字全量收口、流式进行中重连 snapshot 19 条不含增量；
  `[8.5]` 落库 **21 条增量 / 7 次全量收口**、无空增量、无噪音泄漏；
- 前端零改动（`npx tsc -b` 仅作回归确认）。

## 8. 非目标

- **真流式输入**（`--replay-user-messages`）与回合模型改造：CLI 仍是「一问一答 + stdin 常开」；
- **子 agent 的独立渲染分支**：本次只做过滤，不做子 agent 气泡；
- **前端「原始流」开关**：`useChatStream`/`ChatPanel`/`ChatStream` 每帧都是 O(N)/O(N log N) 的全量数组拷贝
  与排序，~8 帧/秒的聚合节流正是它可用的前提；
- **合并 CAP-49 与 runner 的节流实现**（见 §3 末条）；
- **`/chats` 侧的事件补拉上限**（CAP-49 已有自己的上下文裁剪口径，另有其消费方）。

## 9. 实现记录（与初稿的偏差）

| 项 | 初稿 | 实现 | 为什么 |
|---|---|---|---|
| 空白增量的处理 | 计划里写"空白增量忽略" | **保留**仅含空白的增量，只丢弃真正为空的 | 仅含空白的增量在 token 流里是真实内容（词间空格）；丢掉会破坏「增量拼接 == 全量正文」这条不变式 |
| 聚合并发口径 | 未列 | 补 `RunnerDeltaAggregator` 的 4 线程并发用例 | 聚合器被 stdout/stderr 两条读取线程共用，丢字符是静默的 |
| 假执行体 | 只补 partial 帧 | **追加一道串行闸门** | stdin 回调与 `main()` 在 fake 里是并发跑的（E2E 一看到 `permission_request` 就授权，回复会与提问尾部的增量同时流），服务端聚合器**分辨不出回合边界**，于是把两段正文并进同一条 `text_delta`——首轮 E2E 实测 seq=28 一行为提问尾片 + 回复首片，正是「增量拼接 == 全量正文」断言抓出来的。真实 CLI 是单线程事件循环，不会交错，故这是 fake 的保真度问题而非产品缺陷；stdin EOF 的 `result` 也一并走闸门，与「吐完当前回合再退出」一致 |
| `ServerConnection` 加固 | 顺手记录为隐患 | **作为本能力的一环落地**（独立提交，可单独回退） | 开流式后帧率上量级，撞车丢帧从"理论隐患"变成"必然发生" |
| 接口向后兼容 | 未列 | `limit` 默认 `0` = 默认上限 | 既有调用方（前端 `afterSeq=-1`）一行不改 |

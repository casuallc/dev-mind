# CAP-75 飞书集成（通知通道 + 双向机器人网关）

> 能力 ID：CAP-75 ｜ 分类：组装层 ｜ 状态：设计定稿 ｜ 日期：2026-10-13
> 决策记录：事件接入定稿**长连接模式**（lark-oapi SDK WebSocket）——无需公网域名/受信证书，
> SDK 封装建连鉴权与事件解密；194 裸 IP+自签证书走 webhook 回调必被飞书拒绝，域名+ICP 备案
> 成本高，单实例部署下长连接无短板。机器人网关**底座接口化**（devmind-bot），飞书只是第一个
> 适配器（devmind-feishu-bot），未来企微/钉钉 bot 按同一 SPI 接入。

## 1. 目的

两个独立但同源的需求：

- **通知侧**：通知中心（CAP-06 FR-03 通道插件化）现有 ws/log/bark/wecom 四通道，缺飞书。
  补飞书自定义机器人 webhook 通道；顺带根治「外部通道需手工写库填 config」痛点
  （前端声明式 config 表单），并修复种子逻辑 `count()==0` 导致存量库永不补插新通道的缺陷。
- **机器人侧**：在飞书群内 @机器人 完成「问答 + 项目操作」——裸文本走 CAP-30 通用问答，
  斜杠指令做项目绑定、起开发会话、查构建/部署状态，回答回推飞书。

两套飞书实体相互独立：通知通道用**自定义机器人 webhook**（群内向，只发不收）；
双向机器人用**企业自建应用**（appId/appSecret，收发双向）。可分别配置、分别启用。

## 2. 功能需求

### 通知通道（M1）

- **FR-01 feishu 通知通道**：新增 `NotificationChannel` 实现 code=feishu，
  configJson 约定 `{"webhookUrl":"…","secret":"…"}`；secret 非空时按飞书加签规范
  （`HmacSHA256(key=timestamp+"\n"+secret, data="")` → Base64）附带 timestamp/sign。
- **FR-02 通道种子逐 code 兜底**：seed 从 `count()==0` 整段守卫改为逐 code `findByCode`
  兜底插入，存量库升级只补插缺失 code、不覆盖既有配置。
- **FR-03 前端通道 config 声明式表单**：通知设置抽屉按 code 注册表渲染 config 字段
  （bark: server/key；wecom: webhookUrl；feishu: webhookUrl/secret 密码框），保存走既有
  PUT 合并语义；删除「需在数据库中填入」提示文案。

### 双向机器人（M2）

- **FR-04 长连接事件接入**：飞书适配器用 lark-oapi SDK ws.Client 建连，订阅
  `im.message.receive_v1`，标准化为 BotMessage 回调底座；处理器内零业务（3s 窗口约束），
  断线重连由 SDK 负责。
- **FR-05 事件去重**：底座维护 event_id 内存去重表（1h TTL），防 SDK/飞书重推重复执行。
- **FR-06 身份口径**：binding.actor 覆盖优先，否则用该 channel 配置的 defaultActor
  （默认 local）；botExecutor 任务内合成 SecurityContext（仿 OpenApiAuthFilter），
  机器人行为的权限边界 = 该账号既有平台权限，不做第二套授权。
- **FR-07 消息路由与绑定表**：`bot_bindings` 以 (channel, external_chat_id) 唯一；
  懒创建绑定 DevMind 问答 chat（MODEL 执行体优先），`/bind` 绑定项目。
- **FR-08 指令语法**：`/help` `/bind [项目]` `/new` `/session <需求描述>` `/build` `/deploy`；
  裸文本 = 问答。群聊只响应 @机器人，私聊全响应。
- **FR-09 回答回推**：订阅 chat 事件流收集 assistant/result，回合完成（或 120s 超时）后
  一次性 reply；>3500 字符截断 + 平台链接尾注。失败分档 fail-visible 文案
  （未绑定/查无记录/DevMindException/未知异常）。绑定问答在平台被删（或群内换人撞归属
  校验）时续问命中 NOT_FOUND → 丢弃失效引用原地重建新问答，本轮消息不丢（不自愈则
  该群问答永久卡死，只能 /new 手动救）。
- **FR-10 凭证纪律**：appId/appSecret 密文不出 devmind-integration；适配器经
  `FeishuConnector.botClient()` 拿客户端句柄。无启用中的 FEISHU 集成时适配器降级禁用。

### 个人身份与可视化（M3）

- **FR-11 个人身份映射（复用第三方账号）**：飞书身份 = `user_platform_accounts` 一条
  authType=`IDENTITY`（无 secret）记录，(user_id, integration_id=飞书集成, username=open_id)。
  绑定流程：飞书发 `/bindme` → 机器人回身份码（senderId/open_id）→ 用户在
  「设置 → 第三方账号」对该 FEISHU 集成粘贴身份码保存。身份链四级：
  `channel.resolveActor(senderId)`（个人映射）→ binding.actor → channel defaultActor →
  平台默认。`/whoami` 回当前命中级；解绑在设置页/运维页进行（bot 不删数据）。
  SPI 侧：`BotChannelConfigProvider.resolveActor(senderId)` 由适配器实现，bot 底座保持
  不依赖 integration 模块。
- **FR-12 敏感操作门禁**：`/bind` `/session` `/build` `/deploy` 要求已绑定个人身份，
  未绑定回复引导文案并落 REJECTED；`/help` `/new` `/bindme` `/whoami` 与裸文本问答放行。
- **FR-13 交互日志**：`bot_interactions` 表全量记录处理过的事件（channel/chat/sender/actor/
  command/出入站文本截断/status OK|FAILED|DEDUPED|REJECTED/error/duration_ms）；
  群内未 @ 消息不落库（噪音），dedup 命中落 DEDUPED。**回推失败也把行改落 FAILED**
  （error 前缀「回复发送失败」）——问答算出来了但群里没收到，在用户视角就是失败，
  只记日志不改状态会让运维页谎报「成功」。
- **FR-14 界面**：设置页第三方账号面板支持 FEISHU 身份码字段；新增管理页
  「机器人运维」（/admin/bot）：交互日志（分页+筛选+详情）、群绑定（可解绑）、
  用户映射（可解绑）三视图。

## 3. 否决方案

- **webhook 回调端点为主路径**：需受信证书+域名（+国内 ECS 的 ICP 备案），194 自签证书
  必被拒；且要自写验签/AES 解密/重放层。留作 M3 第二接入路径（底座接口化后路由零改动）。
- **open_id ↔ 平台用户映射表入 MVP**：绑定流程成本高，单实例小团队群即信任边界；挪 M3。
- **流式 patch 更新回答**：频率限制+状态机复杂度；MVP 一次性回，「正在思考…」可裁剪。
- **机器人塞进 devmind-integration**：会让凭证/连接器模块反向依赖 chat/session/build/deploy
  全部业务模块，职责错位。

## 4. 架构与模块归属

```
devmind-bot（网关底座，零飞书代码）
  SPI：BotAdapter(code/start/stop/reply) · BotMessage · BotMessageHandler
  路由：BotRouterService（指令解析/绑定/问答/项目操作/回推编排）
  设施：botExecutor(虚拟线程) · event_id 去重表 · bot_bindings 表 · SecurityContext 装配
  依赖：common/auth/chat/session/build/deploy

devmind-feishu-bot（第一个适配器）
  FeishuBotAdapter implements BotAdapter：lark-oapi ws.Client 长连接接入、
    事件标准化、replyText 发送
  依赖：devmind-bot + devmind-integration（FeishuConnector.botClient()）
```

适配器不碰业务，底座不碰协议。新 bot 平台 = 新适配器模块实现 BotAdapter，底座零改动。
（与通知中心 NotificationChannel 按 code 注册同一套路。）

## 5. 数据模型

```
bot_bindings(
  id varchar pk,
  channel varchar(32),          -- feishu / wecom / …
  external_chat_id varchar(64), -- 飞书 chat_id（群=群 id，私聊=p2p id）
  chat_type varchar(16),        -- p2p / group
  owner_sender_id varchar(64),  -- 首个触发者 open_id
  chat_session_id varchar(64),  -- 绑定的 DevMind 问答 id（懒创建，/new 重开；引用失效自愈重建）
  project_id varchar(64),       -- /bind 绑定项目（null=未绑定）
  actor varchar(64),            -- 覆盖运行账号（null=channel defaultActor）
  created_at / updated_at,
  unique(channel, external_chat_id)
)
```

integrations.configJson 扩展槽（FEISHU 类型）：`{"bot":{"enabled":true,"defaultActor":"admin"}}`。
**bot 槽 = 机器人启用开关**：多飞书应用并存（有的只管文档导入、有的只管机器人）时，
`FeishuBotGateway` 只挑 configJson 带 bot 槽且未显式 `enabled:false` 的 ENABLED 集成；
纯文档集成本来就没有 bot 槽，天然不会被机器人选中。前端集成表单对 FEISHU 类型提供
「启用双向机器人」开关 + 默认操作身份输入，保存时自动折进 configJson（保留其他扩展槽）。

M3 新增：

```
bot_interactions(                  -- FR-13 交互日志
  id bigint pk auto,
  channel varchar(32), external_chat_id varchar(128), chat_type varchar(16),
  sender_id varchar(128), actor varchar(64),      -- actor=实际执行身份（四级链命中结果）
  command varchar(16),             -- 指令名 / QA / DEDUPED
  input_text varchar(2000), reply_text varchar(4000),   -- 截断落库
  status varchar(16),              -- OK / FAILED / DEDUPED / REJECTED
  error varchar(512), duration_ms bigint, created_at,
  index(channel, created_at)
)
```

个人身份**不建新表**：复用 `user_platform_accounts`（CAP-35），authType=`IDENTITY`、
username=open_id（FR-11）。

## 6. API 概要

M2 无平台侧新 REST——绑定与操作全部经飞书指令驱动。M1 复用既有
`GET/PUT /api/notification-channels`、`POST /api/notifications/emit`。

M3 新增：
- 个人：`/api/me/platform-accounts` 复用（FEISHU 集成走 IDENTITY 形态，无需新端点）。
- 管理：`GET/DELETE /api/admin/user-platform-accounts`（用户映射，按 type 过滤，ADMIN）；
  `GET/DELETE /api/bot/admin/bindings`（群绑定，ADMIN）；
  `GET /api/bot/admin/interactions`（交互日志分页，channel/status/keyword 过滤，ADMIN）。

## 7. 安全

- 长连接建连鉴权由 SDK 完成（appId/appSecret），无明文回调端点暴露面。
- appId/appSecret 密文不出 integration 模块（IntegrationCipher，enc1: AES-GCM）。
- 机器人权限 = defaultActor/binding.actor 的平台既有权限（FR-06）。
- event_id 去重防重推重复执行（FR-05）。
- 飞书侧限制（记录在案）：仅企业自建应用、每应用最多 50 连接、集群推送（单实例无影响）、
  不支持卡片回调（MVP 纯文本不需要）、3s 处理窗（处理器内零业务，投递即返）。

## 8. 里程碑

- **M1 飞书通知通道**：FR-01~03。（已交付）
- **M2 双向机器人**：FR-04~10（网关底座 + 飞书适配器）。（已交付）
- **M3 个人身份与可视化**：FR-11~14（个人身份映射复用第三方账号、敏感操作门禁、
  交互日志、机器人运维页）。
- **M4 展望**：webhook 回调第二接入路径、机器人主动推送会话进展、
  「正在思考…」占位消息 patch、交互日志保留期清理。

## 9. 验收标准

- M1：新库种子出 5 通道含 feishu；存量库（预置部分通道）启动后补插缺失 code 且不覆盖
  既有配置；feishu 启用但 webhookUrl 空时 emit P0 后 channelStatus.feishu 以 FAILED 开头；
  前端可界面配置 webhookUrl/secret 并保存（合并语义不丢其他 key）；`tests/cap06_verify.py` 全绿。
- M2：单测覆盖指令解析矩阵、路由（绑定懒创建/失败文案）、去重 TTL、飞书事件标准化；
  `tests/cap75_bot_verify.py`（FakeBotAdapter 驱动全路由 + 飞书出站打 mock server）全绿；
  部署机真实联调：飞书后台选长连接保存通过 → 群内 /help → /bind → 问答 → /build。
- M3：`/bindme` 回身份码指引；未绑定发敏感指令被拒并落 REJECTED、问答放行；
  设置页绑定身份码后 `/whoami` 命中个人身份、敏感操作放行；`/api/bot/admin/interactions`
  可见 OK/DEDUPED/REJECTED 行、非 ADMIN 403；运维页三视图可查可解绑；
  `tests/cap75_bot_verify.py` G/H 段全绿。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 未绑定用户问答走 defaultActor 身份 | M3 起敏感操作须绑定（FR-12）；问答额度与 Web 同口径，接受 |
| R2 | 绑定 chat 上下文无限膨胀 | `/new` 重开；M4 自动轮换 |
| R3 | MODEL 执行体额度占用 | 机器人问答与 Web 问答同一额度；120s 超时兜底 |
| R4 | seed 兜底改造影响存量库 | 逐 code findByCode 幂等；旧库启动验证 |
| R5 | im/v1 API 频率限制 | MVP 单条 reply 无压力；patch 增强需限流 |
| R6 | 飞书后台选长连接需客户端在线 | 部署顺序：先上线 M2 再配后台 |
| R7 | open_id 冒填他人身份 | open_id 不可猜（ou_+随机串），只能填自己从 /bindme 拿到的；管理员可在运维页解绑。接受 |
| O1 | 一群一项目是否够用 | M4 `/bind` 切换/多项目群 |
| O2 | bot 配置（defaultActor）放 configJson 明文槽 | 非登录凭证，接受 |
| O3 | 交互日志无保留期 | 量级小（未 @ 不落库）；M4 加 retention |

## 附录 A：真机联调 checklist（M2）

部署顺序有硬约束：飞书后台「事件与回调」选长连接方式时**需客户端在线**才能保存（R6），
所以先上线再配后台。

1. **飞书后台**（open.feishu.cn → 企业自建应用）：
   - 建应用拿 App ID / App Secret；启用「机器人」能力。
   - 权限：`im:message`（收消息）、`im:message:send_as_bot`（回推）。
   - 事件订阅：添加 `im.message.receive_v1`，接收方式选「使用长连接接收事件」
     （此步要求平台侧已在线，先做第 2 步再回来保存）。
   - 发布版本并等企业管理员审批通过。
2. **平台侧**：设置 → 平台集成 → 新建 FEISHU 集成：baseUrl 留空（默认 open.feishu.cn），
   BASIC 双行凭据 = App ID + App Secret；**勾选「启用双向机器人」**（保存为 configJson 的
   bot 槽，多飞书应用并存时机器人只认带槽的集成），可填「机器人默认操作身份」
   （不配则回落 `devmind.bot.default-actor=local`）。**改配后重启应用**——长连接只在启动时建立
   （回推链路免重启，收事件要重启）。
3. **验证**：启动日志见 `飞书机器人长连接已启动`（无 ENABLED 集成时是降级 WARN）→
   群内 @机器人 `/help` → `/bind <项目名>` → 裸文本问答 → `/build`。
4. **个人绑定（M3）**：每位开发者在飞书给机器人发 `/bindme` 拿到自己的身份码 →
   平台「设置 → 第三方账号」对该 FEISHU 集成粘贴身份码保存 → 之后其敏感操作
   （/bind /session /build /deploy）以个人身份执行；未绑定发敏感指令会被拒并收到绑定引导。
   管理员在「机器人运维」页查看交互日志/绑定关系/用户映射。
5. **排错**：收不到事件查后台事件订阅是否保存成功（长连接须在线）；
  有事件无回复查 `bot 回复发送失败` 日志（多为缺 `im:message:send_as_bot` 权限）；
  回复内容异常查 `bot 消息处理失败`（带 eventId）。


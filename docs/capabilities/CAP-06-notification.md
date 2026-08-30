# CAP-06 通知中心

> 能力 ID：CAP-06 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

把"有事情需要人关注"变成**及时、分级、可远程处理**的通知。解决"PowerShell 多开时 agent 卡住/完成却不知道"的痛点。核心能力：事件路由、分级推送、多通道、快捷动作、防打扰。

## 2. 功能需求

- **FR-01 事件总线**：应用内事件（状态机迁移、会话状态翻转、执行器完成/失败）→ 按「事件 × 接收者」路由为通知。
- **FR-02 分级**：
  - **P0 立即推**：等待确认、等待授权、等待输入、执行失败 → 浏览器 Notification + 声音 + 外部通道（Bark/企业微信 Webhook 推手机）；
  - **P1 聚合推**：开发任务完成、部署完成等 → 浏览器通知，多条合并（"3 个会话已完成"）；
  - **P2 静默进中心**：里程碑、agent 提议沉淀经验等。
- **FR-03 通道插件化**：通知通道 SPI：`站内（WebSocket）` / `浏览器（Service Worker）` / `Bark` / `企业微信 Webhook`；每通道可配置启用、开关、分级阈值。
- **FR-04 快捷动作**：通知携带 `actions`（如 确认下一步 / 跳过 / 继续 / 允许授权）；Web 端一键执行；手机端打开链接在页面上一键处理——支持**远程回复**。
- **FR-05 防打扰**：免打扰时段、同类型 N 分钟内去重、单会话静默、按项目/会话设置通知偏好。
- **FR-06 通知中心**：未读列表、历史、按类型筛选；点击通知跳转对应实体（会话/文档/部署单）。

## 3. 插件化接口

- 通道 SPI：`NotificationChannel.send(notification) → status`，实现可插拔注册（Bark 即一个 HTTP 实现）。
- 路由 SPI：`NotificationRouter.route(event) → recipients + level + actions`，供各能力注册自己的事件路由规则。

## 4. 依赖关系

- 依赖：CAP-01（接收者解析、actor）。
- 被依赖：CAP-05（等待类事件）、各执行器（完成/失败）、流程层（阶段产出就绪）。

## 5. 数据模型

```
notifications(id, user_id, level[P0|P1|P2], event_type, title, body,
              entity_type, entity_id, actions(json), channel_status,
              read_at, created_at)
notification_channels(id, code, enabled, config(json), level_threshold)
notification_prefs(user_id, mutes(json), quiet_hours, per_session_silence)
```

## 6. API 概要

```
GET    /notifications?level=&unreadOnly=    通知中心
POST   /notifications/{id}/read             标记已读
POST   /notifications/{id}/action          执行快捷动作 {action: "confirm"|"skip"|...}
WS     /notifications/stream               实时推送（前端）
CRUD   /notification-channels              通道配置
GET/PUT /notification-prefs                通知偏好
```

## 7. 验收标准

- 会话进入 WAITING_INPUT 时收到 P0 浏览器通知 + Bark 手机推送；
- 通知可携带快捷动作，Web 端一键确认"下一步"后状态机推进；
- 免打扰时段不推送 P0 以下通知；
- 同一事件 5 分钟内不重复推送。

## 8. MVP 范围（暂不做）

邮件通道、多语言模板、通知统计报表。

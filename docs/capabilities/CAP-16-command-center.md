# CAP-16 指挥中心（Command Center）

> 能力 ID：CAP-16 ｜ 分类：组装层 ｜ 状态：草案 ｜ 日期：2026-08-31

## 1. 目的

系统首页。目标（蓝图 §5.1）：

> 让用户 30 秒了解整个软件工厂正在发生什么。

聚合各能力的运行状态为一个全局视图：哪些需求在推进、哪些会话在跑、什么事在等人、最近哪里失败了。

## 2. 功能需求

- **FR-01 需求状态分布**：全部项目的需求按状态计数（DRAFT/ANALYZING/DESIGNING/IN_PROGRESS/ACCEPTANCE/DONE）。
- **FR-02 活跃会话**：RUNNING/WAITING_INPUT/WAITING_AUTH 会话列表（WAITING_* 高亮 = 在等人）。
- **FR-03 待办确认**：ACCEPTANCE 状态需求（待人工验收）+ DRAFT 状态方案（待确认）。
- **FR-04 最近失败**：构建/部署/测试/发版中最近的 FAILED 记录，按时间倒序合并。
- **FR-05 跳转**：每条目可跳到对应会话详情/项目详情页。
- **FR-06 自动刷新**：前端 10s 轮询。

## 3. 插件化接口

- `GET /api/dashboard` 单一聚合端点（devmind-app 组装层，与 RequirementOverview 同模式）；
  各能力模块不感知本能力，新增聚合维度只改组装层。

## 4. 依赖关系

- 依赖全部能力模块的只读仓库（findAll 内存聚合，本地规模足够；数据量上来后再加专用查询）。

## 5. 数据模型

无新增表，纯聚合视图。

## 6. API 概要

```
GET /api/dashboard → {
  requirements: { DRAFT: n, ANALYZING: n, ... },
  activeSessions: [{id, projectId, taskSpec(截断), status, createdAt}],
  pendingAcceptance: [{id, projectId, code, title}],
  pendingDesigns: [{id, projectId, requirementId, version, docId}],
  recentFailures: [{type: BUILD|DEPLOYMENT|TEST_RUN|RELEASE, id, projectId, label, time}]
}
```

## 7. 验收标准

- 首页打开即见各状态需求数、活跃会话（含等待人高亮）、待验收需求、待确认方案、最近失败；
- 点击条目跳转正确；10s 自动刷新；空数据时各区块有 Empty 占位。

## 8. MVP 范围（暂不做）

指标趋势图/Metrics、Agent 效率统计、成本管理、自定义看板布局（V2 范畴）。

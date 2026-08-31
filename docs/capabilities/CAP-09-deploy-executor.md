# CAP-09 部署执行器（Deploy Executor）

> 能力 ID：CAP-09 ｜ 分类：执行器 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

经 CAP-07 适配器执行部署计划：拉取产物 → 备份 → 部署 → 启动 → 健康检查；**任一步失败自动回滚**并告警。部署执行本身幂等，部署过程全量审计。

## 2. 功能需求

- **FR-01 部署计划**：由项目预定义的部署脚本模板 + 参数（产物引用、目标服务器、环境）渲染生成；计划在执行前**可见**（步骤列表）。
- **FR-02 执行步骤**：`拉取产物 → 备份当前版本 → 部署/滚动更新 → 启动 → 健康检查`；逐步状态实时展示（WebSocket）。
- **FR-03 回滚**：任一步失败 → 自动回滚到备份版本 → 记录 `ROLLED_BACK` + P0 告警；支持手动触发回滚。
- **FR-04 部署记录**：deployment 关联 build、server、workItemId；幂等（同 build 重复部署可识别）。
- **FR-05 状态机**：`PLANNED / RUNNING / SUCCESS / FAILED / ROLLED_BACK`。
- **FR-06 通知**：部署完成/失败按分级通知（CAP-06）。
- **FR-07 部署确认门**：执行前可要求确认（流程层使用；直接调用本能力时可不强制，由上层决定）。

## 3. 插件化接口

- 部署模板 SPI：`DeployPlanGenerator`，默认实现=基于 CAP-07 `ScriptTemplate` 渲染计划。
- 执行 SPI：`DeployExecutor.execute(plan) → stepResults`，内部逐步骤调用 CAP-07 适配器。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（目标服务器）、CAP-07（执行通道）、CAP-08（构建产物）。
- 被依赖：CAP-10（部署完成后对目标做冒烟/API 测试）、流程层（部署节点）。

## 5. 数据模型

```
deployments(id, project_id, work_item_id?, server_id, build_id, env,
            plan(json), status, current_step, logs_ref,
            backup_ref, rollback_of?, started_at, finished_at, created_by)
deployment_steps(id, deployment_id, seq, name, type[artifact|backup|deploy|start|health],
                 status, detail, started_at, finished_at)
```

## 6. API 概要

```
POST   /deployments                    创建部署单 {projectId, serverId, buildId, plan?}
GET    /deployments/{id}               详情（计划 + 步骤状态）
POST   /deployments/{id}/execute       执行部署
POST   /deployments/{id}/confirm       执行前确认（流程层用）
POST   /deployments/{id}/rollback      手动回滚
WS     /deployments/{id}/stream        步骤/日志实时流
GET    /deployments?projectId=&status= 历史
```

## 7. 验收标准

- 对测试服务器执行部署成功，步骤状态逐项可见；
- 人为制造失败（如启动命令出错）时自动回滚到备份版本并告警；
- 部署日志与审计完整；
- 同 build 重复部署能被识别。

## 8. MVP 范围（暂不做）

滚动更新灰度比例策略、蓝绿发布、环境级审批流（确认门由流程层控制）。

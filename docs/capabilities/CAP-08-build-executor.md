# CAP-08 构建执行器（Build Executor）

> 能力 ID：CAP-08 ｜ 分类：执行器 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

按项目的构建配置执行构建。每个项目自定义**有序脚本步骤**（可组合多个脚本），执行位置**本机/远程可配置**（远程经 CAP-07 适配器），产物登记到构建记录，日志实时流式展示。

## 2. 功能需求

- **FR-01 构建配置**：每项目一份有序构建步骤列表，每步 `{name, command|script, workDir, env, params}`；支持"调用多个脚本"组合（如先 build.sh 再 package.sh）。
- **FR-02 执行位置可配置**：`executor = local | remote`（remote 需指定目标 server + 用其白名单脚本模板方式执行）；每项目可选，构建时可临时指定。
- **FR-03 构建上下文**：触发时携带 `(projectId, commit, branch, workItemId?)`，写请求头/工作目录。
- **FR-04 产物登记**：构建成功后登记 `artifactRef`（制品路径 / 镜像 tag / 版本号），供部署/发版引用。
- **FR-05 日志实时流**：stdout/stderr 经 WebSocket 实时推前端；日志全量留存。
- **FR-06 状态机**：`QUEUED / RUNNING / SUCCESS / FAILED`；失败保留退出码与错误摘要。
- **FR-07 触发方式**：手动触发（看板按钮）+ 上层流程调用；同一项目并发构建数限制。

## 3. 插件化接口

- 执行器 SPI：`BuildExecutor.execute(buildContext, config) → BuildResult`；本地与远程各一个实现。
- 本地实现复用 CAP-07 的 `ScriptTemplate` 渲染（渲染为本地 shell 执行）以统一参数化逻辑。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（项目/构建配置）；CAP-07（远程执行时）。
- 被依赖：CAP-09（取产物部署）、CAP-11（取制品发版）、流程层（构建节点）。

## 5. 数据模型

```
build_configs(project_id, steps(json), executor, remote_server_id)
builds(id, project_id, work_item_id?, commit, branch, executor,
       steps, artifact_ref, status, exit_code, error_summary,
       logs_ref, started_at, finished_at, created_by)
```

## 6. API 概要

```
GET    /projects/{id}/build-config           查看/编辑构建配置
POST   /projects/{id}/builds                触发构建 {commit?, branch?, executor?}
GET    /builds/{id}                         构建详情（含 artifact_ref）
WS     /builds/{id}/logs                    构建日志实时流
GET    /builds?projectId=&status=           构建历史
```

## 7. 验收标准

- 配置"两个脚本步骤"的项目能一次构建完成，步骤按序执行、日志实时可见；
- 本机/远程两种执行位置均可用（远程经 SSH 服务器验证）；
- 成功后 artifact_ref 正确登记，失败有退出码与错误摘要；
- 构建历史可按项目/状态筛选。

## 8. MVP 范围（暂不做）

分布式构建集群、跨项目构建产物缓存、构建超时策略的可视化配置（先固定默认值）。

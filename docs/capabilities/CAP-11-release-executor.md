# CAP-11 发版执行器（Release Executor）

> 能力 ID：CAP-11 ｜ 分类：执行器 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

走项目配置的 **Nexus 脚本模板**推送制品完成发版，并打版本 tag、记录发版单。执行位置本机/远程可配置，全程审计。发版是流水线收尾节点，也可独立手动调用。

## 2. 功能需求

- **FR-01 发版配置**：每项目一份：Nexus 推送脚本模板（引用 CAP-07 `script_templates`，如 `nexus-push.tpl`，占位符参数化）、目标仓库、版本规则（如 `semver` 或项目自定义）。
- **FR-02 版本管理**：版本号生成/递增（依据规则从已发版本推算，或手工指定）；版本号贯穿发版记录。
- **FR-03 执行**：渲染模板（产物引用 + 版本号 + 目标仓库）→ 本机或经 CAP-07 远程执行 → Nexus 推送成功即成功。
- **FR-04 打 tag**：给项目 git 仓库打版本 tag（`v<version>`）；tag 前可做实现与方案一致性校验（可选，流程层触发）。
- **FR-05 发版记录**：release 关联 requirementId / projectId / build（制品来源），可追溯"哪个需求、哪个构建、什么版本、何时发"。
- **FR-06 状态机**：`PLANNED / RUNNING / SUCCESS / FAILED / ROLLED_BACK`（回滚=移除 Nexus 制品引用 + 删除 tag，作为一次回滚记录）。
- **FR-07 通知**：发版完成/失败分级通知（CAP-06）。

## 3. 插件化接口

- 推送 SPI：`ReleasePusher`，默认实现=Nexus 脚本模板执行（本机/远程复用 CAP-07）；可扩展其它制品库（Maven/Gradle 插件、对象存储等）。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（发版配置）、CAP-07（远程执行时）、CAP-08（制品来源）。
- 被依赖：流程层（发版节点）。

## 5. 数据模型

```
release_configs(project_id, template_ref, repository, version_rule)
releases(id, project_id, requirement_id?, build_id, version, status,
         artifact_ref, nexus_ref, tag_name, rollback_of?,
         started_at, finished_at, created_by)
```

## 6. API 概要

```
GET    /projects/{id}/release-config        查看/编辑发版配置
POST   /releases                           创建发版单 {projectId, buildId?, version?, requirementId?}
POST   /releases/{id}/execute              执行（渲染模板→推送→打 tag）
POST   /releases/{id}/rollback             回滚（移除制品引用 + 删 tag）
GET    /releases?projectId=&status=        发版历史
```

## 7. 验收标准

- 配置 Nexus 推送模板的项目能成功发版，制品在 Nexus 可见；
- 版本号按规则生成/递增，git tag 正确打上；
- 发版记录关联 requirement/build，可追溯；
- 发版失败可回滚并留痕。

## 8. MVP 范围（暂不做）

制品审批流（多人确认）、多渠道发布（同时推多仓库）、发版工单系统集成。

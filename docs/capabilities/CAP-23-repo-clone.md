# CAP-23 项目仓库从 Git 克隆（GitLab/GitHub）

> 能力 ID：CAP-23 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-02

## 1. 目的

CAP-02 项目注册目前只支持**本机已有 git 仓库路径**（`validateRepo` 校验 `.git` 存在）。
平台部署到服务器后，用户没有"本地路径"可填，需要支持：创建项目/添加仓库时填
GitLab/GitHub 远端地址，由服务端**异步克隆**到本机工作区，按项目分目录管理。

克隆完成后，既有消费方（CAP-05 会话 worktree、CAP-08 本地构建、CAP-11 发版脚本、
CAP-18/22 push 分支/tag）全部基于本地路径工作，**零改动受益**。

## 2. 产品决策（已定稿）

1. **工作区目录**：默认 `data/repositories/<projectId>/`（跟随启动目录，与 H2/密钥同级），
   `devmind.project.workspace-root` 可配置覆盖；主库子目录 `main`，非主库按 `<repo名>` 子目录。
2. **异步克隆 + 进度日志**：创建后立即返回，虚拟线程后台克隆；每库独立状态机
   `NONE / CLONING / READY / FAILED`；日志走 CAP-12 `ExecutionLogHub`，WS 实时查看；失败可重试。
3. **认证**：复用 CAP-18/22 Integration 实例（PAT 已 AES-GCM 加密落库）；克隆时
   `IntegrationService.tokenOf()` 内存解密 + HTTPS URL 内嵌 `oauth2:<token>@`（仅进程参数）；
   不选集成 = 公开仓库匿名克隆；**SSH 不支持**（沿用 GitRemoteOps 既有约束）。
4. **范围**：主库 + 多库（project_repos）均支持克隆。
5. **本地路径模式保留**：存量项目与本地开发场景完全不受影响，创建表单两种来源切换。

## 3. 功能需求

- **FR-01 克隆模式创建项目**：`POST /api/projects` 接受 `sourceType=CLONE` + `remoteUrl`
  + 可选 `integrationId` + 可选 `defaultBranch`。服务端计算目标路径
  `<workspaceRoot>/<projectId>/main`，落库后发布 `project.repo.clone-requested` 领域事件，
  立即返回（克隆异步进行）。`sourceType=LOCAL`（默认）保持现有行为不变。
- **FR-02 多库克隆**：`POST /api/projects/{id}/repos` 同样支持 `sourceType=CLONE`，
  目标路径 `<workspaceRoot>/<projectId>/<repo名>`（重名追加 `-<repoId>`）。
- **FR-03 状态机与重试**：每库 `cloneStatus`：NONE（本地库）→ CLONING → READY / FAILED；
  FAILED 可经 `POST /api/projects/{id}/repos/{repoId}/clone` 重试，或
  `POST /api/projects/{id}/clone/retry` 重试项目内全部 FAILED 库。CLONING 中重复触发返回 409。
- **FR-04 实时日志**：克隆输出（`git clone --progress`，脱敏后）经 ExecutionLogHub 广播，
  WS `/ws/repo-clones/clone-<repoId>` 实时订阅；全量日志落 `project_repos.clone_logs`，
  `GET /api/projects/{id}/repos/{repoId}/clone/logs` 可回放。
- **FR-05 默认分支探测**：未指定 defaultBranch 时，克隆成功后取
  `git symbolic-ref --short refs/remotes/origin/HEAD` 回写。
- **FR-06 主库状态镜像**：`projects.clone_status` 镜像主库克隆状态（沿用
  `projects.path`/`default_branch` 镜像模式），列表页可直接展示徽标。

## 4. 校验规则

- `remoteUrl` 必须是 http/https；ssh（`git@`/`ssh://`）明确报错；
  `file://` 仅在 `integrationId` 为空时放行（本地验证/内网通道）。
- `integrationId` 非空时：Integration 必须存在、ENABLED、type ∈ {GITLAB, GITHUB}，
  且其 `base_url` 的 host 与 remoteUrl 的 host 一致（防拿 A 平台 token 撞 B 平台）。
- CLONE 库的 `path` 由系统管理，禁止通过 update 接口修改；改 remoteUrl/integrationId
  后不自动重克隆，需显式触发重新克隆。
- 目标路径 normalize 后必须 `startsWith(workspaceRoot)`（防 `..` 逃逸）；repo 子目录名
  白名单字符 `[a-zA-Z0-9._-]`。

## 5. 安全约束（沿用 CAP-18 并新增）

- PAT 加密落库、任何 API 响应不回显明文、不进日志与异常消息。
- **token 残留防护（新增，clone 特有）**：`git clone <urlWithToken>` 会把带 token 的 URL
  持久化进 `.git/config` 的 `remote.origin.url`（push 场景无此问题，URL 仅是进程参数）。
  克隆结束后必须立即 `git -C <dir> remote set-url origin <cleanUrl>` 清除；
  后续 fetch/push 仍走 `GitRemoteOps.withToken` 显式注入，不依赖 origin 凭据。
- 所有 git 进程输出经 `sanitize`（token 明文 + URL 编码形态 → `***`）后才进日志/落库。
- 删除项目/仓库**不删除磁盘目录**，仅日志提示人工清理（与既有 deleteRepo 语义一致）。

## 6. 数据模型

`project_repos` 新增列（ddl-auto=update 自动演进）：

| 列 | 类型 | 说明 |
|---|---|---|
| `source_type` | varchar(16) default `'LOCAL'` | LOCAL / CLONE |
| `integration_id` | bigint nullable | 弱关联 integrations.id（不设外键） |
| `clone_status` | varchar(16) default `'NONE'` | NONE / CLONING / READY / FAILED |
| `clone_error` | varchar(1024) | 失败摘要（已脱敏） |
| `clone_logs` | CLOB | 全量克隆日志（WS 快照 + REST 回放） |
| `cloned_at` | timestamp | 最近成功时间 |

`projects` 新增 `clone_status` varchar(16) nullable（主库镜像，null = 纯本地项目）。

## 7. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects` | sourceType=CLONE 创建克隆项目（FR-01） |
| POST | `/api/projects/{id}/repos` | sourceType=CLONE 添加克隆仓库（FR-02） |
| POST | `/api/projects/{id}/repos/{repoId}/clone` | 触发/重试单库克隆（FR-03） |
| POST | `/api/projects/{id}/clone/retry` | 重试项目内全部 FAILED 库（FR-03） |
| GET | `/api/projects/{id}/repos/{repoId}/clone/logs` | 克隆日志回放（FR-04） |
| WS | `/ws/repo-clones/clone-{repoId}` | 实时克隆日志（FR-04） |

## 8. 模块归属与依赖

- **克隆编排放 devmind-integration**：依赖方向 `integration → project` 且 integration
  持有 token 解密能力（token 不出模块边界）；增加对 devmind-execution 的依赖
  （ExecutionLogHub/ExecutionWsHandler）不成环。
- **触发反转**：project 模块创建 CLONE 行后发布 `project.repo.clone-requested`
  领域事件（devmind-common DomainEventPublisher），integration 侧 `@EventListener` 接收启动克隆。
- 依赖：CAP-02（项目/多库模型）、CAP-12（执行底座日志 Hub）、CAP-18/22（Integration/凭据/GitRemoteOps）。

## 9. 验收标准

- 以 `sourceType=CLONE` + GitLab/GitHub 私有仓库地址 + PAT 集成创建项目，后台克隆成功，
  状态 CLONING→READY，`data/repositories/<projectId>/main` 为可用 git 仓库；
- 克隆完成后 `.git/config` 的 `remote.origin.url` **不含 token**；克隆日志与 API 响应
  全程无 token 明文；
- 匿名克隆公开仓库（不选集成）成功；填错地址/凭证失败进入 FAILED，重试可达 READY；
- 多库项目各库独立克隆、独立状态，单库失败不影响其他库；
- 前端可实时看到克隆日志，项目列表/仓库列表展示克隆状态；
- 本地路径模式（LOCAL）存量行为完全回归不破。

## 10. 暂不做

SSH 私钥克隆、浅克隆/稀疏克隆选项、克隆完成自动触发摘要扫描、磁盘目录自动清理、
存量仓库的 `git fetch` 定时同步（会话 worktree 创建时已有 `git fetch origin`）。

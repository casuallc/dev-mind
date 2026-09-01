# CAP-18 第三方平台集成（GitLab 先行）

> 能力 ID：CAP-18 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-01

## 1. 目的

把平台的研发主线从「本地闭环」延伸到外部研发平台：Work Item 开发完成后**推送分支并创建 MR**，
发版打 tag 后**推送 tag 并创建 GitLab Release**，让追溯链从
`WI → 本地 commit` 延伸为 `WI → MR → tag → Release`。

设计分两层，避免把「git 远程操作」与「平台 API」揉在一起：

```
第 1 层  Git 远程操作（与平台无关）
         push 分支 / push tag / 远程分支管理
         = git CLI + 凭据，GitLab/GitHub/Gitea 通用

第 2 层  平台连接器 SPI（平台相关）
         IntegrationConnector
         ├── GitLabConnector   MR / Release / 项目信息        ← MVP
         ├── GitHubConnector   PR / Release                  （后续）
         └── JiraConnector     Issue 同步（无 git 能力）      ← CAP-19 已落地
```

术语约定：**Integration** = 一个外部平台实例的配置（base_url + 凭据）；
**Binding** = Integration 与项目仓库（CAP-02 project_repos）的绑定关系；
**External Link** = 内部实体 ↔ 外部对象的映射（WI ↔ MR、Release ↔ GitLab Release），
保证幂等与追溯。

## 2. 功能需求

- **FR-01 Integration CRUD**：登记外部平台实例：type（GITLAB / GITHUB / JIRA）、name、
  base_url、auth_type（MVP 仅 PAT）、token、status（ENABLED / DISABLED）；
  token **加密落库**（复用 `data/auth.key` 派生密钥，AES-GCM），任何 API 响应不回显明文。
- **FR-02 连接测试**：`POST /integrations/{id}/test` 调平台 API 验证 token 有效性与权限范围
  （GitLab：`/api/v4/user` + `scope: api`），返回诊断信息。
- **FR-03 项目绑定**：把 Integration 绑到 project_repo（按 remote_url 匹配或人工选择
  GitLab project id）；一个项目可绑多个 Integration（如 GitLab + Jira），
  同类型仅允许一个 ENABLED。
- **FR-04 分支推送**：WI 开发完成后，将 `wi/<seq>-<slug>` 分支 push 到绑定远程。
  触发方式 MVP 为**手动动作**（WI 详情页/API 调起），后续可由
  `workitem.status.changed` 事件自动触发。push 走 git CLI（HTTPS + token 注入，
  不落 .git-credentials 明文）。
- **FR-05 创建 MR**：对已推送分支创建 Merge Request（source=WI 分支，target=仓库默认分支），
  title/description 由 WI 标题/spec 渲染；结果登记 External Link
  （WI ↔ MR iid + web_url）。重复调用按 External Link 幂等，已有未关闭 MR 时返回既有 MR。
- **FR-06 tag 推送 + GitLab Release**：CAP-11 发版打 tag 成功后，push tag 到绑定远程，
  并创建 GitLab Release（name=版本号，description=发版单摘要）；
  结果登记 External Link（Release ↔ tag/release）。回滚时**不删远程 tag**（只记录，防误操作）。
- **FR-07 External Link 登记与查询**：统一映射表，记录 (internal_type, internal_id) ↔
  (integration_id, external_type, external_key, external_url)；
  支持按内部实体反查（WI 详情页展示「MR !123」链接）。
- **FR-08 审计**：所有出站调用经 CAP-01 actor 记录审计（谁、何时、对哪个平台、做了什么），
  失败原因落调用日志表（不存 token）。

## 3. 插件化接口

```java
interface IntegrationConnector {                      // SPI，按 type 注册
    IntegrationType type();
    TestResult testConnection(Integration cfg);       // FR-02
    List<ExternalProject> listProjects(Integration cfg);          // 绑定辅助
    MergeRequestRef createMergeRequest(Integration cfg, MrSpec spec);   // FR-05
    ReleaseRef createRelease(Integration cfg, ReleaseSpec spec);        // FR-06
}
```

- 凭据解析统一走 `CredentialResolver`（按 auth_type 取明文，仅内存使用，不进日志）；
- git 远程操作为独立 `GitRemoteOps` 服务（push branch/tag），不依赖具体 Connector，
  凭据注入方式按 auth_type 可替换；
- 自动触发策略（WI DONE 是否自动 push+建 MR）做成项目级开关配置，默认关。

## 4. 依赖关系

- 依赖：CAP-01（actor + 审计 + 密钥复用）、CAP-02（project_repos 绑定挂载点）、
  CAP-13（WI 分支约定、External Link 挂 WI/Release）。
- 被依赖：CAP-11（发版后 push tag + 建 Release 的调用方）、
  流程层（后续消费 MR 状态推进 WI，入站 webhook 属后续阶段）。
- 不改被依赖方表结构；CAP-11 仅增加「发版成功后调用 CAP-18」的可选钩子。

## 5. 数据模型

```
integrations(id, type[GITLAB|GITHUB|JIRA], name, base_url,
             auth_type[PAT], secret_enc, status[ENABLED|DISABLED],
             config_json?, created_by, created_at, updated_at)
integration_bindings(id, integration_id, project_id, repo_id,
                     external_project_key,        -- GitLab project id / path
                     status, created_at)
external_links(id, project_id, integration_id,
               internal_type[WORK_ITEM|RELEASE|...], internal_id,
               external_type[MR|TAG_RELEASE|ISSUE|...],
               external_key, external_url, status, created_at)
integration_calls(id, integration_id, action, internal_type?, internal_id?,
                  result[SUCCESS|FAILED], error?, actor, created_at)   -- 调用审计，不含 secret
```

## 6. API 概要

```
CRUD   /integrations                                   平台实例管理（仅 ADMIN 写）
POST   /integrations/{id}/test                         连接测试
CRUD   /projects/{pid}/integrations                    项目绑定（binding）
POST   /projects/{pid}/work-items/{wid}/push           推送 WI 分支到绑定远程
POST   /projects/{pid}/work-items/{wid}/merge-request  创建/复用 MR，登记 External Link
GET    /projects/{pid}/links?internalType=&internalId= External Link 反查
GET    /projects/{pid}/integration-calls               调用日志
```

发版钩子：CAP-11 execute 成功后内部调用 `IntegrationService.pushTagAndRelease(releaseId)`，
项目未绑定 Integration 时静默跳过。

## 7. 主流程（WI → MR）

```
① 配置   ADMIN 登记 GitLab Integration（base_url + PAT）→ 连接测试通过
② 绑定   项目绑定 Integration，选定 GitLab project（按 remote_url 自动匹配）
③ 开发   WI 会话在 worktree 完成开发（既有 CAP-05 流程不变）
④ 推送   WI 详情触发「推送」→ git push wi/<seq>-<slug>（token 注入）→ 调用日志
⑤ 建 MR  触发「创建 MR」→ GitLab API 建 MR → External Link 登记 → WI 详情展示 MR 链接
⑥ 发版   CAP-11 打 tag 成功 → push tag + 创建 GitLab Release → External Link 登记
```

## 8. 安全

- PAT 加密落库、不明文回显、不进日志/审计；仅 ADMIN 可管理 Integration；
- 出站调用按 Integration.status 与绑定状态双重校验，DISABLED 立即拒绝；
- GitLab 自托管实例的 base_url 校验协议（仅 http/https）与端口，防 SSRF 指到内网任意服务
  （后续可加管理员白名单）。

## 9. 验收标准

- 可登记 GitLab Integration（PAT 加密存储），连接测试给出明确诊断；
- 项目绑定后，WI 可一键 push 分支、创建 MR，重复创建幂等返回既有 MR；
- 发版后 tag 推送成功且 GitLab 侧出现对应 Release；未绑定项目的发版流程不受影响；
- WI 详情能反查到 MR 链接；全部出站调用有审计记录且不含 token 明文。

## 10. MVP 范围（暂不做）

入站 webhook（MR 合并/Pipeline 状态回写）、GitHub/Jira Connector、OAuth App 认证、
MR 合并动作与评审流、issue 双向同步、自动触发策略（WI DONE 自动 push）、
远程 tag 删除/回滚联动。

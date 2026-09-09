# CAP-35 统一第三方账号体系（平台实例 + 个人账号绑定）

> 能力 ID：CAP-35 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-09
>
> 演进取代 CAP-24（用户级 Git 身份与凭证），并重构 CAP-18/19/22 的凭据使用方式。

## 1. 目的

现状里「第三方平台凭据」同一概念存在**两套割裂模型**：

| | Integration（CAP-18/19/22） | user_git_credentials（CAP-24） |
|---|---|---|
| 维护人 | 仅 ADMIN | 每用户自助 |
| 覆盖平台 | GitLab / GitHub / Jira | 仅 git 平台，**Jira 无个人通道** |
| 实例地址 | 登记一次 | 每用户手填同一 base_url，靠 host 字符串匹配 |
| 消费场景 | clone、Jira 轮询、tag/Release、**MR 创建、Jira 状态转换、worklog 回写** | 会话署名 env、WI push |

两个硬伤：

1. **CAP-24 的"可归因到人的写操作走个人身份"原则只落地了一半**——WI push 个人优先，
   但建 MR/PR（`IntegrationService.createMergeRequest`）、Jira 状态转换与工时回写
   （`JiraIssueActionService` 全程 `tokenOf(integration)`）这些人点按钮的写操作，
   在外部平台上全部表现为机器人同一人；
2. **实例信息重复登记、匹配脆弱**——用户配个人 PAT 要重新手填平台地址，host 字符串
   对不上就静默回退机器人，无引导。

本能力把模型统一为两层：**Integration 收敛为"平台实例登记 + 可选机器人凭证"**，
**用户个人账号统一为"绑定到实例的 UserPlatformAccount"**，并把所有可归因到人的写操作
收敛到同一条身份解析链。

```
ADMIN 侧                        用户侧
┌─────────────────────────┐    ┌──────────────────────────────────┐
│ Integration（实例登记）   │    │ UserPlatformAccount（我的账号）    │
│  type/name/base_url/     │◄───┤  (user_id, integration_id) 唯一   │
│  status + 机器人凭证(可选) │    │  PAT/BASIC 密文 + git 署名        │
└─────────────────────────┘    └──────────────────────────────────┘
        │ 自动化路径专用                     │ 人触发写操作优先
        ▼                                  ▼
  clone/fetch、Jira 轮询、            WI push、建 MR/PR、
  tag push、建 Release、             Jira transition、worklog 回写、
  编排器自动 push                    会话提交署名 env
```

## 2. 产品决策（已定稿）

1. **个人账号绑定到 Integration 实例**（不再自由填 base_url）：用户从 ADMIN 已登记的
   ENABLED 实例中选择绑定，`(user_id, integration_id)` 唯一。实例未登记 = 找 ADMIN，
   不允许用户私自登记实例（防 SSRF 面扩散，与 CAP-18 §8 一致）。
2. **一次到位**：模型/UI 统一 + 身份链重构同批落地。建 MR/PR、Jira transition、
   worklog 回写全部切「个人优先」。
3. **Jira 个人账号 PAT 与 BASIC 都支持**：BASIC 密文格式沿用 `"username\npassword"`，
   与 Integration 同一加密设施（enc1: AES-GCM）。
4. **机器人凭证变为可选**：Integration 可不配 token 纯作实例登记（此时自动化路径
   报明确错误引导 ADMIN 补配）；配了机器人凭证的 Integration 行为与现状一致。
5. **存量迁移零丢失**：`user_git_credentials` 按 host 匹配 Integration 一次性迁移；
   无匹配实例的 host 自动登记一条 Integration（type 按 host 含 "github" 判 GITHUB、
   否则 GITLAB，name = host，无机器人凭证，ENABLED）再绑定。

## 3. 功能需求

- **FR-01 UserPlatformAccount CRUD**：`GET/PUT/DELETE /api/me/platform-accounts`。
  `GET` 返回**全部 ENABLED 实例 + 我的绑定状态**（未绑定实例也列出，引导配置）；
  `PUT /api/me/platform-accounts/{integrationId}` 为 upsert（绑定/更新我的账号）。
  字段：secret（PAT 或 BASIC 密码）、username（仅 Jira BASIC）、
  gitAuthorName/gitAuthorEmail（仅 git 平台，必填，沿用 CAP-24 语义）。
  本人作用域隔离不变；密文任何视图不回显。
- **FR-02 个人连通性自检**：`POST /api/me/platform-accounts/{integrationId}/test`，
  复用对应 Connector 的 `testConnection` 但用**我的凭据**（git 平台成功即 PAT 有效 +
  署名已配；Jira 为 `/myself`）。不再要求用户输入 remoteUrl（实例地址来自 Integration）。
- **FR-03 统一身份解析链（核心）**：`IntegrationService` 内收敛一个
  `resolveWriteIdentity(actorUsername, integrationId)`，返回
  `WriteIdentity(token, source[PERSONAL|BOT])`：
  - **人触发写路径**一律个人账号 → 机器人凭证 → 抛 400 并提示
    「请先在 我的→第三方账号 绑定该平台账号」（不静默回退以外的第三条路）：
    WI push（CAP-24 FR-04 既有，链路改走新模型）、**创建 MR/PR（CAP-18 FR-05 改造）**、
    **Jira transition（CAP-19 FR-08 改造）**、**worklog 回写（CAP-27 FR-03 改造）**；
  - **自动化路径固定机器人**：clone/fetch（CAP-23/26/29）、Jira 轮询（CAP-19 FR-03）、
    tag push + Release（CAP-18 FR-06）、CAP-15/17 编排器自动 push。Integration 未配
    机器人凭证时报错「该实例未配置平台凭证（自动化需要），请联系 ADMIN」；
  - 响应与 `integration_calls` 审计标注 `identity_source`（PERSONAL/BOT），沿用
    CAP-24 FR-04 既有做法推广到全部写路径。
- **FR-04 会话署名注入适配**：`GitIdentityProvider.resolveAuthor(username, repoHost)`
  SPI 签名与语义不变；内部实现改为 repoHost → 匹配 ENABLED git Integration →
  查我的 UserPlatformAccount 取署名。未命中回退 displayName/username（现状不变）。
- **FR-05 存量迁移**：应用启动一次性迁移器（幂等，迁移后打标记行/日志）：
  按 `user_git_credentials.base_url` host 匹配 Integration.base_url host →
  改写为 UserPlatformAccount；无匹配的按 §2.5 自动登记实例再绑定。迁移完成后
  旧 Entity/Repository/Controller 删除（H2 遗留空表不清理，ddl-auto 不管删表）。
- **FR-06 前端统一**：
  - 个人菜单「我的 Git 凭证」→「第三方账号」（路由 `/me/git-credentials` →
    `/me/accounts`）：实例卡片/表格列出全部 ENABLED 实例，每行 = 类型 Tag + 名称 +
    地址 + 绑定状态 +（git 平台）署名 + 操作（绑定/编辑/自检/解绑）；未绑定实例
    显示「未绑定」引导态；
  - 后台「平台集成」页文案更新：凭据说明改为「平台凭证（机器人）：供克隆、
    Jira 轮询、打 tag/Release 等自动化使用；人触发的写操作优先使用操作人的个人账号」，
    token 改为非必填；
  - 各触发点报错文案带跳转引导（MR 创建 / Jira 操作 / 登记工时 400 时提示去绑定）。
- **FR-07 CAP 文档同步**：CAP-24 标记「已由 CAP-35 取代」；CAP-18 FR-05、CAP-19 FR-08、
  CAP-27 FR-03 补注身份链改走 CAP-35；README 索引更新。

## 4. 插件化接口

```java
// devmind-integration 内部（token 不出模块边界原则不变）
record WriteIdentity(String secret, AuthType authType, String username /*BASIC用*/,
                     IdentitySource source /*PERSONAL|BOT*/) {}
WriteIdentity resolveWriteIdentity(String actorUsername, IntegrationEntity integration);

// common SPI 不变：GitIdentityProvider.resolveAuthor(username, repoHost)
```

- Connector SPI **零变更**：各方法仍收 `(IntegrationEntity cfg, String token)`，
  个人 BASIC 凭据按既有 `"username\npassword"` 格式解密传入，Connector 无感知；
- 个人自检复用 Connector `testConnection`（FR-02），Connector 不需要知道凭据来源。

## 5. 依赖关系

- 依赖：CAP-01（认证上下文/actor）、CAP-18/19/22（Integration/Connector/加密设施/
  GitRemoteOps）、CAP-05/21（会话 env 注入，仅适配实现不改接口）、CAP-27（worklog 通道）。
- 被依赖：无新增（消费方全部在 integration 模块内部改链）。
- 不改表结构的外部模块；`integrations` 表仅 secret 语义放宽为可空（应用层，无 DDL）。

## 6. 数据模型

新表 `user_platform_accounts`（ddl-auto=update 自动演进）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | |
| `user_id` | varchar(32) not null | 归属用户（弱关联） |
| `integration_id` | bigint not null | 绑定的平台实例 |
| `auth_type` | varchar(16) not null | PAT / BASIC（不得与 Integration 支持的方式冲突） |
| `username` | varchar(128) | 仅 Jira BASIC 登录名 |
| `secret_enc` | varchar(2048) | enc1: PAT 或 `"username\npassword"` 密文 |
| `git_author_name` | varchar(128) | git 平台必填；Jira 恒空 |
| `git_author_email` | varchar(256) | git 平台必填；Jira 恒空 |
| `created_at` / `updated_at` | timestamp | |

唯一约束：`(user_id, integration_id)`（应用层判重，同 CAP-24 手法）。
`integrations.secret_enc` 语义放宽为可空（§2.4），无 DDL 变更。

## 7. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/me/platform-accounts` | 全部 ENABLED 实例 + 我的绑定状态（脱敏） |
| PUT | `/api/me/platform-accounts/{integrationId}` | 绑定/更新我的账号（upsert；secret 留空 = 不修改） |
| DELETE | `/api/me/platform-accounts/{integrationId}` | 解绑我的账号 |
| POST | `/api/me/platform-accounts/{integrationId}/test` | 我的凭据连通性自检（FR-02） |

行为变化（既有端点签名不变，身份链变化）：

- `POST /projects/{pid}/work-items/{wid}/merge-request`（CAP-18）：改个人优先，
  响应新增 `identitySource`（PERSONAL/BOT）；
- `POST /projects/{pid}/requirements/{rid}/jira/transitions`（CAP-19 FR-08）与
  `.../jira/worklog`（CAP-27 FR-03）：改个人优先，审计标注身份来源；
- `/api/me/git-credentials` 整组端点随旧模型删除（内部系统无兼容负担）。

## 8. 主流程（绑定 → 人触发写操作）

```
① ADMIN   登记 Integration（GitLab/Jira/...），机器人凭证按需配置
② 用户    我的→第三方账号：看到实例列表 → 绑定（粘 PAT / 填 BASIC）→ 自检通过
③ 开发    会话提交自动以我的署名（FR-04，现状延续）
④ 写操作  WI push / 建 MR / Jira 状态转换 / 登记工时 → resolveWriteIdentity
          → 命中我的账号：外部平台显示本人操作；未命中：回退机器人并审计标注 BOT
⑤ 自动化  clone / Jira 轮询 / tag / Release 固定机器人，无个人身份介入
```

## 9. 安全约束（沿用 CAP-18/23/24）

- 密文 enc1: AES-GCM 落库，任何 API 响应不回显明文、不进日志与异常消息；
- token 不出 integration 模块边界；`resolveWriteIdentity` 仅在模块内消费；
- 个人账号仅本人可读写（认证上下文 userId 过滤）；
- 实例登记仍仅 ADMIN（用户不可自填 base_url，SSRF 面不扩大）。

## 10. 验收标准

- 用户 A 绑定 GitLab 个人 PAT 后创建 MR，GitLab 侧 MR 作者为 A，响应
  `identitySource=PERSONAL`；用户 B 未绑定，创建 MR 走机器人（`BOT`）；
- 用户绑定 Jira 个人 PAT（或 BASIC）后执行状态转换/登记工时，Jira 侧操作人
  为本人；未绑定走机器人；
- 未配机器人凭证的 Integration：clone / Jira 轮询给明确错误；人触发写操作不受影响
  （个人账号命中即可用）；
- 存量 `user_git_credentials` 迁移后：会话署名、WI push 个人优先行为回归不破，
  原 `/me/git-credentials` 页数据完整出现在「第三方账号」页；
- 凭证明文不出现在任何响应/日志/异常消息。

## 11. 暂不做

- OAuth 授权码流程；同实例多账号；凭证过期提醒与轮换；
- ADMIN 查看他人账号明细（最多见绑定人数统计）；
- 未登记实例的用户自助接入（需要 = 找 ADMIN 登记）；
- Jira 回写操作在外部平台失败后的重试队列（沿用现状同步报错）。

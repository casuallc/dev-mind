# CAP-24 用户级 Git 身份与凭证

> 能力 ID：CAP-24 ｜ 分类：底座 ｜ 状态：需求定稿（2026-09-03） ｜ 日期：2026-09-03

## 1. 目的

CAP-18/23 的凭证模型是**平台级共享**：Integration 实例（PAT）由 ADMIN 维护，项目仓库行
弱引用它，克隆 / push 分支 / push tag 全部以同一个"机器人账号"身份执行。多人并行开发
同一项目时有两个缺口：

1. **提交身份缺失**：平台没有任何地方设置 `git user.name/user.email`，Agent 会话产出的
   commit 作者落到执行节点系统 git 全局配置或 claude 默认值，**谁发起的会话无从追溯**；
2. **写操作身份共享**：WI 分支 push（CAP-18）用的是项目绑定的 Integration token，
   所有人的推送在 GitLab/GitHub 上都表现为同一个人。

本能力引入**用户级 Git 凭证与提交身份**：每个用户自助维护自己在各 git 平台的 PAT 与
署名（name/email），平台按"谁操作谁署名"注入——Agent 提交以会话发起人署名，
用户触发的 push 用其个人 PAT。

**边界（明确不变）**：平台自动化身份仍走共享 Integration——克隆（读操作）、release
打 tag、CAP-15/17 编排器自动触发的 push 保持机器人身份，这是业界通行的 CI bot 模式。
本能力只覆盖**可归因到人的写操作**。

## 2. 产品决策（已定稿）

1. **用户自助维护**：个人设置页管理"我的 Git 凭证"（每 git 平台 host 一条），不再只有
   ADMIN 能配凭证；加密复用 CAP-18 的 `IntegrationCipher`（enc1: AES-GCM）。
2. **提交身份随凭证走**：凭证记录上携带 `gitAuthorName / gitAuthorEmail`（同一人在
   GitLab 与 GitHub 的邮箱可能不同，不宜放 users 表全局列）；未配凭证时回退
   `displayName || username` + 不注入 email（保持现状行为）。
3. **身份注入走进程环境变量，不写 git config**：会话拉起 claude 子进程时注入
   `GIT_AUTHOR_NAME/EMAIL` + `GIT_COMMITTER_NAME/EMAIL`。**不用** `git config user.*`
   的原因：git worktree 默认与主仓共享 `.git/config`，并行会话并发写同一配置文件会
   互相覆盖（race）；`--worktree` 作用域需开启 `extensions.worktreeConfig`，侵入存量
   仓库。环境变量随进程隔离，天然无竞争，且覆盖 Agent 在会话内的一切提交。
4. **push 凭证优先级链**：操作用户个人 PAT（按 remoteUrl host 匹配）→ 项目绑定
   Integration → 报错提示配置。克隆链路（CAP-23）不变，仍只用 Integration/匿名。
5. **范围**：仅 http/https PAT，沿用 GitRemoteOps 既有约束（SSH 不支持）。

## 3. 功能需求

- **FR-01 我的 Git 凭证 CRUD**：`GET/POST/PUT/DELETE /api/me/git-credentials`，
  字段：label、baseUrl（host 用于匹配）、PAT、gitAuthorName、gitAuthorEmail。
  任何登录用户可管理**自己的**记录（user_id 隔离，ADMIN 也不能读他人明文——
  密文任何视图不回显）。
- **FR-02 连通性自检**：`POST /api/me/git-credentials/{id}/test` 执行
  `git ls-remote <baseUrl 派生的探测地址>`（内存注入 token，输出脱敏），
  返回成功/失败摘要。
- **FR-03 会话提交身份注入**：创建会话时按 `createdBy` + 主库 remoteUrl host 解析
  身份（个人凭证 → 回退 displayName/username），经 `SessionExecutor.LaunchContext`
  注入子进程环境变量；远程 Agent 节点（CAP-21）会话同理由 runner 透传 env。
- **FR-04 push 身份优先个人**：CAP-18 `pushBranch`（WI 分支推送）改为先取触发用户的
  个人 PAT（host 匹配），命中则用个人身份 push；未命中回退项目 Integration（现状行为）。
  执行结果视图标注实际所用身份来源（PERSONAL / INTEGRATION）。
- **FR-05 身份解析 SPI**：`devmind-common` 新增 `GitIdentitySpi`（按 userId + repoHost
  返回 token/authorName/authorEmail 三元组，token 仅内存），devmind-integration 实现；
  消费方（session、integration 自身 push 路径）以 `ObjectProvider` 探测注入，不成环。

## 4. 校验规则

- `baseUrl` 必须 http/https 且可解析出 host；同一用户同一 host 唯一（以 host 判重）。
- `gitAuthorEmail` 必须合法邮箱格式；`gitAuthorName` 非空（缺省回退 displayName）。
- PAT 更新时留空 = 不修改（沿用 Integration 编辑语义）。
- 个人凭证 push 前校验其 host 与目标 remoteUrl host 一致（沿用 CAP-23 防撞平台校验）。

## 5. 安全约束（沿用 CAP-18/23）

- PAT 以 enc1: AES-GCM 密文落库，任何 API 响应不回显明文、不进日志与异常消息。
- token 不出 integration 模块边界：会话侧只拿到 env 注入后的进程，push 侧由
  integration 内部完成 `withToken` 注入与输出脱敏（token → `***`）。
- 个人凭证仅本人可读写（以认证上下文 userId 过滤，不依赖前端隐藏）。

## 6. 数据模型

新表 `user_git_credentials`（ddl-auto=update 自动演进）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | |
| `user_id` | varchar(32) not null | 归属用户（users.id，弱关联不设外键） |
| `label` | varchar(128) | 显示名（如"公司 GitLab"） |
| `base_url` | varchar(512) not null | 平台地址，host 用于匹配 remoteUrl |
| `secret_enc` | varchar(2048) | enc1: PAT 密文 |
| `git_author_name` | varchar(128) | 提交署名 name |
| `git_author_email` | varchar(256) | 提交署名 email |
| `created_at` / `updated_at` | timestamp | |

唯一约束：`(user_id, base_url_host)`（host 归一化小写后判重，应用层保证）。

## 7. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/me/git-credentials` | 我的凭证列表（脱敏视图） |
| POST | `/api/me/git-credentials` | 新增（FR-01） |
| PUT | `/api/me/git-credentials/{id}` | 修改（secret 留空不变） |
| DELETE | `/api/me/git-credentials/{id}` | 删除 |
| POST | `/api/me/git-credentials/{id}/test` | 连通性自检（FR-02） |

既有 API 行为变化：CAP-18 `POST /api/projects/{pid}/work-items/{wid}/push`（WI 分支）
响应新增 `identitySource` 字段（PERSONAL / INTEGRATION）。

## 8. 模块归属与依赖

- **表与加解密放 devmind-integration**：持有 `IntegrationCipher` 与 GitRemoteOps，
  token 不出模块边界的原则与 Integration 一致；控制器路径 `/api/me/...` 挂在该模块。
- **身份注入在 devmind-session**：`SessionExecutor` 启动子进程前经 `GitIdentitySpi`
  （ObjectProvider 探测，integration 未装配时回退现状）取身份并入 env。
- 依赖：CAP-01（用户/认证上下文）、CAP-18/22（加密设施/GitRemoteOps/push 链路）、
  CAP-05（会话拉起）、CAP-21（远程节点 env 透传）。
- 前端：`features/auth`（或个人中心既有目录）新增"我的 Git 凭证"页，自包含。

## 9. 验收标准

- 用户 A、B 在同一项目各自创建会话并让 Agent 提交，两处 worktree 的 commit
  author/committer 分别为 A、B 署名（`git log` 可验证），互不串身份；
- 用户配了个人 PAT 后触发 WI 分支 push，GitLab/GitHub 上 push 者显示为本人，
  响应 `identitySource=PERSONAL`；未配则回退 Integration（`identitySource=INTEGRATION`）；
- 凭证明文不出现在任何 API 响应、日志、异常消息；`data` 落库为 enc1: 密文；
- 未配任何凭证的存量行为完全回归不破（单用户/机器人场景无感）；
- 远程 Agent 节点会话同样带上发起人身份 env。

## 10. 暂不做

- SSH 私钥凭证；users 表增加全局 email 列；公共仓库注册表（多项目共享同一 remote
  的去重与克隆缓存，另行立 CAP 论证）；凭证过期提醒与轮换；OAuth 授权码流程。

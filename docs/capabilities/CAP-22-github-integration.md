# CAP-22 GitHub 集成（PR / Release）

> 能力 ID：CAP-22 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-02

## 1. 目的

CAP-18 连接器 SPI 的第二个 git 平台实现：让绑定 GitHub 仓库的项目走完与 GitLab 相同的
`WI → push 分支 → 建 PR → 发版 push tag → 建 Release` 追溯链。
范围同时覆盖 **github.com**（API 入口 `https://api.github.com`）与
**GitHub Enterprise**（API 入口 `<base_url>/api/v3`），由 base_url 自动分流。

不改任何既有结构：`IntegrationConnector` SPI、`IntegrationService` 分发、
`GitRemoteOps`（git push 与平台无关，HTTPS URL 内嵌 `oauth2:<token>@`，GitHub 同样接受）、
`external_links` 幂等登记均原样复用，本能力只新增一个 `GitHubConnector`。

## 2. 功能需求

- **FR-01 连接测试**：`GET /user` 验证 token 有效；从响应头 `X-OAuth-Scopes`
  给出权限范围诊断（建 PR/Release 需要 `repo` scope；fine-grained PAT 无该头时提示按仓库授权）。
- **FR-02 项目列表**：`GET /user/repos?affiliation=owner,collaborator,organization_member&sort=updated&per_page=100`，
  External Link 的 project key = `owner/repo`（`full_name`），默认分支取 `default_branch`。
- **FR-03 创建 PR（对应 GitLab MR）**：`POST /repos/{owner}/{repo}/pulls`
  （head=WI 分支，base=仓库默认分支，title/body 由 WI 渲染——服务层既有逻辑不变）。
  同源分支已有未关闭 PR 时 GitHub 返回 **422**，降级为查询既有 open PR 返回（reused=true，幂等）。
- **FR-04 创建 Release**：`POST /repos/{owner}/{repo}/releases`（tag_name/name/body）。
  该 tag 的 Release 已存在（422）时查 `GET /releases/tags/{tag}` 返回既有（reused=true）。
- **FR-05 审计与安全**：全部沿用 CAP-18——PAT 加密落库不明文回显、出站调用进
  `integration_calls` 且不含 token、仅 ADMIN 可管理 Integration。
  token 只出现在 `Authorization: Bearer` 请求头，不进日志与异常消息。

## 3. 与 GitLab 连接器的差异点

| 维度 | GitLab | GitHub |
|------|--------|--------|
| API 基址 | `<base_url>/api/v4` | github.com → `https://api.github.com`；GHE → `<base_url>/api/v3` |
| 认证头 | `PRIVATE-TOKEN` | `Authorization: Bearer <token>` |
| 项目 key | 数字 id 或 `group/path`（需 %2F 编码） | `owner/repo`（路径参数，逐段编码，不编码 `/`） |
| MR 对应物 | Merge Request（iid） | Pull Request（number） |
| 幂等冲突码 | 409 | 422 |

## 4. API 与数据模型

零新增——复用 CAP-18 全部端点与表结构，`integrations.type` 枚举值 `GITHUB` 早已预留。
前端「平台集成」页类型下拉新增 GitHub 选项。

## 5. 依赖关系

- 依赖：CAP-18（SPI / 服务层 / 凭据加密 / GitRemoteOps / External Link），CAP-11（发版钩子）。
- 无新依赖方；CAP-11 与 WI 详情的 push/建 MR 入口对项目绑定的平台类型透明。

## 6. 验收标准

- 可登记 GITHUB 类型 Integration（github.com 或 GHE 地址），连接测试给出用户与 scope 诊断；
- 绑定 GitHub 仓库后，WI 可 push 分支、创建 PR，重复创建幂等返回既有 PR；
- 发版后 tag 推送成功且 GitHub 侧出现对应 Release，重复执行幂等；
- 全部出站调用有审计记录且不含 token 明文。

## 7. 暂不做

GitHub Issues 同步（issue 跟踪走 Jira/CAP-19）、入站 webhook（PR 合并回写 WI 状态）、
GitHub App / OAuth 认证、GraphQL API。

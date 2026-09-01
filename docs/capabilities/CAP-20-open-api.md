# CAP-20 开放 API（open-api）+ API Key 管理 + AI 项目接入助手

## 1. 背景与目标

外部工具/客户端（CI 脚本、第三方平台、AI 助手）需要程序化接入 dev-mind。会话 JWT 是「人」的凭证，不适合机器集成。
本 CAP 提供：

- **开放面 API**：`/open-api/v1/**`，覆盖项目接入全链路（项目/服务器/模板/环境/构建/部署/发版/触发构建）。
- **API Key 管理**：AK/SK 密钥对（`api_keys` 表），管控台 `/admin/keys` 页（仅 ADMIN）。
- **独立认证**：HMAC-SHA256 签名（不走 JWT 会话体系），secret 不上网。
- **AI 项目接入助手**：open-api 的首个客户端——后台「项目管理」页「AI 智能接入」按钮，描述项目情况后全自动写入配置并触发构建验证。

## 2. 模块与边界

- 新 Maven 模块 `devmind-open-api`（包 `com.devmind.openapi`）：ApiKeyEntity/Service、OpenApiAuthFilter、OpenApiV1Controller、ApiKeyAdminController。
- v1 端点是**薄层**：全部委托现有能力模块 service，DTO 与管控台面 `/api/**` 完全同构。
- AI 接入助手在 devmind-app（`com.devmind.onboarding`）：OnboardingController + OnboardingService + OnboardingPrompt。
- 删除 CAP-01 遗留的 api token 半成品（ApiTokenEntity/issueToken/verifyToken，无调用方）——由 api_keys 正式取代。

## 3. 签名规范（HMAC-SHA256）

请求头三件套：

```
X-Access-Key: ak_<24hex>
X-Timestamp:  <epoch 秒>           # ±5 分钟防重放窗口
X-Signature:  <hex HMAC>
```

```
stringToSign = METHOD + "\n" + path（含 query） + "\n" + timestamp + "\n" + sha256hex(body || "")
X-Signature  = hex(HMAC-SHA256(key = sha256hex(sk), stringToSign))
```

**密钥材料设计**：服务端只存 `sha256hex(sk)`（secret 不明文落库），客户端本地对 sk 做同样计算得到 HMAC 密钥——
secret 既不上网也不可逆。签名比对用常量时间比较。

参考实现：`scripts/openapi.sh`（bash + openssl + sha256sum，Git Bash 可跑）：

```bash
export DEVMIND_AK=ak_xxx DEVMIND_SK=sk_xxx
scripts/openapi.sh GET  /open-api/v1/projects
scripts/openapi.sh POST /open-api/v1/projects '{"name":"x","path":"D:/repo"}'
# DEVMIND_BASE_URL 覆盖默认 http://localhost:8080
```

服务端：`OpenApiAuthFilter`（仅拦 `/open-api/**`）缓存请求体 → 校验 key 存在/启用/未过期 → 时间戳窗口 →
重算签名常量时间比对 → 通过则设置等价 ADMIN 的 Authentication（principal `apikey:<名称>`，各表 created_by 可追溯）
并刷新 lastUsedAt；失败统一 401 ApiError。

**过滤器接线**：auth 模块定义 `PreJwtAuthFilter` 基类（SPI），SecurityConfig 用 `ObjectProvider` 收集注册到
JwtAuthFilter 之前——open-api 依赖 auth（IdentityService），auth 不反向依赖 open-api，无循环。
`/open-api/**` 不在 `/api/**` 规则内，落到 `anyRequest().permitAll()`，由本 filter 兜底（未带签名头直接 401）。

## 4. API Key 管理

- `api_keys` 表：id、accessKey（uk，`ak_`+24hex）、secretHash（SHA-256）、name、enabled、expiresAt（可空=永久）、lastUsedAt、createdBy、createdAt。
- 管理端点（走 JWT，SecurityConfig 限定仅 ADMIN）：
  - `POST /api/open-keys` {name, expiresAt?} → **secret 明文仅此响应一次**
  - `GET /api/open-keys` / `PUT /api/open-keys/{id}` {enabled} / `DELETE /api/open-keys/{id}`
- 前端：`/admin/keys`（后台菜单「API 密钥」）——列表（状态/过期/最近使用）、新建弹窗、签发后一次性展示 ak/sk
  （一键复制为 `DEVMIND_AK`/`DEVMIND_SK` 环境变量）、启停/删除。

## 5. open-api v1 端点清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/open-api/v1/projects` | 项目列表/创建 |
| POST | `/open-api/v1/projects/{id}/servers` | 登记 SSH 服务器（accessConfig 为字符串化 JSON，凭据 AES-GCM 落库） |
| POST | `/open-api/v1/script-templates` | 命令模板白名单（CAP-07） |
| POST | `/open-api/v1/projects/{id}/environments` | 环境（绑服务器 + 变量） |
| PUT | `/open-api/v1/projects/{id}/build-steps` | 构建步骤整表替换（有序） |
| PUT | `/open-api/v1/projects/{id}/build-config` | 构建配置 |
| PUT | `/open-api/v1/projects/{id}/deploy-config` | 部署计划（steps + rollbackSteps） |
| POST | `/open-api/v1/projects/{id}/release-config` | 发版配置（注意是 POST，与 /api 面一致） |
| POST | `/open-api/v1/projects/{id}/builds` | 触发构建 |
| GET | `/open-api/v1/builds/{id}`、`/open-api/v1/builds/{id}/logs` | 查状态/日志 |

## 6. AI 项目接入助手

- 入口：后台「项目管理」页「AI 智能接入」按钮 → 描述弹窗 → `POST /api/projects/onboard`（仅 ADMIN）→ 跳转 `/sessions/{id}` 实时观看。
- 流程（OnboardingService）：`apiKeyService.issue("onboard-<ts>", now+2h)` 一次性密钥 → 渲染内置 prompt（OnboardingPrompt）→
  起**不挂项目的裸会话** + `permissionMode=bypassPermissions`（全自动）→ 返回 sessionId。
- prompt 要点：角色与目标；ak/sk export + openapi.sh 用法；端点清单与 JSON 骨架；标准顺序（先 `ls` 探测路径/脚本真实存在 →
  建项目→服务器→模板→环境→构建步骤→build-config→deploy-config→（可选）release-config→触发构建验证）；
  ctyunmanager 真实案例 few-shot；红线（路径不存在停下标「需人工确认」、不瞎编端口/参数、不改用户仓库文件、摘要不含 secret）；
  最终输出「接入摘要」契约。

### 已知取舍

- **ak/sk 以明文出现在会话事件流**：一次性密钥 2h 过期 + 仅 ADMIN 可发起，可接受；长期密钥不要用于会话。
- **裸会话 cwd**：无项目会话 cwd = 后端进程工作目录（spring-boot:run 时为 devmind-app 模块目录），prompt 要求 agent 先 `ls` 定位 `scripts/openapi.sh`。
- **prompt 维护点**：open-api 端点/DTO 变更时必须同步 `OnboardingPrompt`（本文件第 5 节为契约源头）。

## 7. 验证清单

- [x] 单测：ApiKeyService（签发只存哈希/禁用/过期失败）、OpenApiAuthFilter（合法签名放行/query 参与签名/错 sk/过期时间戳/篡改 body/缺头均 401、非开放面不拦截）
- [x] E2E：`POST /api/open-keys` 签发 → `scripts/openapi.sh GET /open-api/v1/projects` 返回项目列表；错 sk 401 `签名校验失败`
- [ ] E2E（AI 助手）：项目管理页粘真实描述 → 会话 DONE 后新项目配置齐全、自触发构建 SUCCESS

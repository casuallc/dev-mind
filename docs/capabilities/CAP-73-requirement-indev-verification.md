# CAP-73 需求开发中验证（feature 分支构建 → 需求绑定环境部署 → 冒烟 → 结果回灌会话）

> 能力 ID：CAP-73 ｜ 分类：流程层 ｜ 状态：草案（2026-10-10）
> 演进自 CAP-17（执行链编排）与 CAP-51（需求粒度工作区）；消费 CAP-08/09/10 执行器与 CAP-34/36 执行通道。

## 1. 目的

现状两条主线是断开的：需求主线（会话开发 → 收口合并）与执行主线（构建/部署/测试，
以基线分支或定稿制品为输入）只在**一批需求全部完成后**才相交。AI 在会话里写完代码
看不到改动跑起来的样子，验证全靠收口后人工走构建部署测试；发现问题时会话上下文已冷，
再开新会话修复，循环成本高。

本能力把执行链**下沉到需求粒度**，让验证发生在收口之前：需求开发过程中，agent
**自主触发**「推送需求分支 → 构建 → 部署到该需求绑定的验证环境 → 冒烟测试」，
结果回灌进行中会话，agent 据此前置迭代（改 → commit → 再验证），人只在验收与收口
两个节点介入。

定稿决策（2026-10-10 与需求方确认）：

| 决策点 | 结论 |
|---|---|
| 环境粒度 | **绑定需求**（需求可能是 bug，bug 的复现/验证环境是任意的，不限 DEV/TEST） |
| 构建对象 | **commit 后的需求分支 HEAD**（`feature/req-<id>`），不支持未提交改动 |
| 触发权 | **agent 自主触发**，平台配限额兜底；同时保留人工触发入口 |
| 工作单元 | 验证是**需求级**动作，需求下 WI 共享同一验证链与历史 |

不变量：**PROD 环境永远不可作为验证绑定目标**（CAP-17 红线不松动——生产部署永远人工）。

## 2. 功能需求

### FR-01 需求验证环境绑定

`requirements` 表加 `verify_environment_id`（可空，引用 environments 表 id；
存量行 null = 未绑定）。需求新建/编辑表单增加「验证环境」选择器，选项 = 该项目
environments 中**除 PROD 外**的全部环境（bug 类需求绑复现所在环境，功能类需求一般
绑 DEV/TEST）。

未绑定 = 验证功能不可用：人工按钮置灰带引导，agent 触发返回结构化错误
（`VERIFY_ENV_UNBOUND`，文案引导先绑定环境），不静默降级。

### FR-02 验证执行链（verify run）

新实体 `verify_runs` 承载一次验证的完整链路追溯。链分三步，任一步失败即终态
（FAILED + 失败阶段），不推进下一步：

1. **推送需求分支**：把 `feature/req-<id>` 当前 HEAD push 到远端（幂等，复用
   CAP-42 收口帧里的分支 push 通道；分支未推送过 = 首次建立远端引用）。
   构建基准按 CAP-26 口径取 `origin/feature/req-<id>`，不取节点本地工作树。
2. **构建**：复用 CAP-08 项目构建配置，源 ref = 需求分支 HEAD commit
   （`commit_sha` 落 verify_runs 追溯）；产物进入既有制品流。
3. **部署 + 冒烟**：复用 CAP-09 部署（目标环境 = FR-01 绑定环境）与 CAP-10
   该环境绑定的冒烟套件（无绑定套件则跳过测试阶段，状态仍可为 SUCCESS 并标注
   「未执行冒烟」）。

触发即返回 runId，全链异步执行；状态机 QUEUED → PUSHING/BUILDING/DEPLOYING/
TESTING → SUCCESS/FAILED。

### FR-03 Agent 自主触发通道

会话上下文装配（CAP-33/34 ContextPackage）为挂需求且已绑定验证环境的会话物化
助手脚本 `.devmind/bin/devmind-verify`（平台物化路径照旧写进 worktree `info/exclude`，
不占 git 跟踪文件），内嵌**会话作用域 token**（复用 CAP-34 context 拉包 token 机制）。
agent 经 Bash 调用 `devmind-verify [--note "..."]`：

- 成功受理：输出 runId 与「结果将回灌本会话」提示，立即返回（不阻塞 agent）；
- 被拒：输出结构化错误码与引导文案（未绑定环境/并发冲突/超限/无进行中需求分支提交）。

skill 注入块（CLAUDE.local.md）同步广告该命令的存在与使用时机
（「完成一个可验证的改动并 commit 后，运行 devmind-verify 验证」）。

### FR-04 限额与互斥

- **需求级互斥**：同需求同时刻仅一个 verify run（QUEUED/进行中再触发 → 409），
  与 CAP-51 需求工作区互斥同口径。
- **项目级开关**：`projects.verify_in_dev_enabled`（默认关，Boolean 可空 + getter
  兜底，**不加 @ColumnDefault**——CAP-72 同款红线）；关闭时人工/agent 触发一律 403。
- **日配额**：`projects.verify_daily_quota`（整数，默认 20，0 = 不限）；
  超限 429，文案告知次日重置与人工调整入口。

### FR-05 结果回灌与通知

verify run 终态时：

- **回灌会话**：该需求有进行中会话 → 以 system 事件注入会话事件流
  （RunnerSessionRegistry 注入先例）：结果 + commit_sha 短号 + 失败阶段与日志尾部
  （截断）+ 成功时的访问地址。agent 下一轮自然看到，自主决定修复迭代。
- **无进行中会话**：落验证历史 + CAP-06 通知需求负责人（成功带访问地址，失败带
  失败摘要与日志链接）。

### FR-06 人工入口与收口可见性

- 需求详情页新增「验证」Tab：verify run 历史列表（状态/触发人 AGENT·MANUAL/
  commit/耗时/失败阶段）+ 「立即验证」按钮（与 agent 触发同链同限额同互斥）。
- 收口确认弹窗展示**最近一次 verify run 状态**（成功绿标/失败红标/从未验证灰标），
  仅提示不门禁——「验证通过才允许收口」的硬门禁另立 CAP。

## 3. 插件化接口

无新增 SPI。编排落 **devmind-flow**（CAP-17 PipelineOrchestrator 先例：flow 已直接
依赖 devmind-build/deploy/test 实现）；会话事件注入走既有
`RunnerSessionRegistry.injectSystemEvent` 通道；agent 触发物化复用 CAP-34
ContextPackage 管线。

## 4. 依赖关系

依赖 CAP-13（Requirement 模型与需求级归属）、CAP-51（需求分支 `feature/req-<id>`
与需求级互斥口径）、CAP-08/09/10（构建/部署/测试执行器）、CAP-12（执行底座与日志
Hub）、CAP-34/36（exec 帧与 runner 执行、ContextPackage 物化）、CAP-26（远端引用
基准）、CAP-06（通知）、CAP-72（项目级开关先例）。

## 5. 数据模型

```
requirements 加列：verify_environment_id BIGINT NULL  -- environments.id

verify_runs(
  id                BIGINT PK,
  requirement_id    BIGINT NOT NULL,
  project_id        VARCHAR(32) NOT NULL,
  trigger_type      VARCHAR(8) NOT NULL,     -- AGENT / MANUAL
  triggered_by      VARCHAR(64),             -- 人工触发用户名；agent 触发为会话 id
  environment_id    BIGINT NOT NULL,
  commit_sha        VARCHAR(64),
  build_id          BIGINT, deployment_id BIGINT, test_run_id BIGINT,
  stage             VARCHAR(16),             -- QUEUED/PUSHING/BUILDING/DEPLOYING/TESTING
  status            VARCHAR(16),             -- RUNNING/SUCCESS/FAILED
  failure_summary   LONGVARCHAR,             -- 失败阶段+日志尾部（脱敏截断）
  access_url        VARCHAR(512),            -- 部署成功后的访问地址
  created_at, finished_at
)
```

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/requirements/{id}/verify` | 人工触发（限额/互斥/开关同 agent 通道） |
| GET | `/api/requirements/{id}/verify-runs` | 验证历史（分页） |
| POST | `/api/verify/trigger?token=` | agent 通道（会话作用域 token 自证，免登录） |

需求读写 DTO 增 `verifyEnvironmentId` 字段。

## 7. 验收标准

1. 需求绑定验证环境后，会话内 agent 执行 `devmind-verify` 触发成功：verify run
   走完 推送→构建→部署→冒烟 全链，进行中会话事件流收到结果事件（含访问地址）。
2. 构建基准是需求分支**已 push 的 HEAD commit**；未 commit 的 worktree 改动不参与
   （agent 触发时无新提交 = 拒绝并提示先 commit）。
3. 同需求并发触发第二次 → 409；超过日配额 → 429；未绑定环境 → 结构化错误引导；
   项目开关关闭 → 403。
4. 构建/部署/冒烟任一失败：链终止，会话收到失败阶段 + 日志尾部，历史可查。
5. 无进行中会话时触发（人工）：结果走通知，需求「验证」Tab 可见完整历史。
6. 收口确认弹窗显示最近验证状态；PROD 环境不出现在绑定选项中。
7. 存量需求（verify_environment_id = null）行为零变化：按钮置灰、agent 通道报
   VERIFY_ENV_UNBOUND。

## 8. MVP 范围与非目标

**MVP**：FR-01~06 全量（单环境绑定、串行三步链、限额互斥、回灌与通知、验证 Tab）。

**非目标（另立 CAP）**：

- 验证通过作为收口硬门禁（本期仅可见性提示）；
- 容器级环境隔离 / 每需求独立 slot（同环境并发需求互相覆盖部署物是**已知限制**，
  规避方式 = 为并行需求绑不同环境或环境 variables 隔离）；
- agent 修复迭代的最大轮次自治策略（靠日配额兜底，精细预算另议）；
- 未提交改动直接构建（定稿决策 2 已否决）；
- PROD 环境的任何自动动作（红线）。

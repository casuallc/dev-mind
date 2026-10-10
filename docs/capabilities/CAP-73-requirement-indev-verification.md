# CAP-73 需求开发中验证（需求分支增量补丁 → 既有环境快速更新 → 冒烟 → 结果回灌会话）

> 能力 ID：CAP-73 ｜ 分类：流程层 ｜ 状态：草案（2026-10-11）
> 演进自 CAP-17（执行链编排）与 CAP-51（需求粒度工作区）；消费 CAP-08/10 与 CAP-34/36 执行通道。

## 1. 目的

现状两条主线是断开的：需求主线（会话开发 → 收口合并）与执行主线（构建/部署/测试，
以基线分支或定稿制品为输入）只在**一批需求全部完成后**才相交。AI 在会话里写完代码
看不到改动跑起来的样子，验证全靠收口后人工走构建部署测试；发现问题时会话上下文已冷，
再开新会话修复，循环成本高。

本能力把验证**前移到需求开发过程中**，且核心诉求是**快**——不是再造一条全量
「构建 → 部署 → 测试」流水线（那是 CAP-17 已有形态，分钟级起步、与开发节奏不匹配），
而是：**环境上已有跑着的服务，验证 = 把本次改动的文件以补丁方式更新进去 + 冒烟**。
agent 自主触发，秒级~分钟级拿到结果回灌会话，据此前置迭代（改 → commit → 再验证），
人只在验收与收口两个节点介入。

定稿决策（与需求方确认）：

| 决策点 | 结论 |
|---|---|
| 验证方式 | **既有环境增量补丁更新**（变更文件覆盖 + 更新钩子），非全量构建部署 |
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

### FR-02 环境快速更新配置

补丁验证要求环境**已有运行中的服务**且平台知道怎么更新它。environments 增补丁配置
（可空三件套，缺任一 = 该环境不可用于验证）：

- `patch_dir`：服务在该环境节点上的部署目录（补丁覆盖的目标）；
- `patch_hook`：更新后执行的命令（重启进程 / systemctl restart / 热加载脚本；
  空 = 仅覆盖文件不重启，适用于热加载场景）；
- 执行节点取环境既有 `nodeIds` 首节点（CAP-36 口径）。

绑定时不强校验；触发时缺配置报 `VERIFY_ENV_NOT_PATCHABLE` 并引导到环境配置页。

### FR-03 增量验证链（verify run）

新实体 `verify_runs` 承载一次验证的追溯。链分四步，任一步失败即终态
（FAILED + 失败阶段），不推进下一步：

1. **推送需求分支**：`feature/req-<id>` 当前 HEAD push 远端（幂等，复用 CAP-42
   收口帧的分支 push 通道）；无新 commit（HEAD 与上次验证相同）→ 409 拒绝并提示
   先提交新改动。
2. **增量构建**：runner 构建工作区（CAP-36，按 workspaceKey 复用、本地缓存
   与依赖目录保留）checkout 需求分支 HEAD 执行项目构建脚本——增量收益来自
   工作区/依赖缓存复用（构建系统自身的增量编译是项目脚本职责，平台不介入）。
3. **补丁下发**：构建输出与环境节点侧同步清单（`.devmind-verify-manifest.json`，
   文件路径+哈希，存部署目录旁）比对，**仅把变更文件经 exec 帧下发覆盖**到
   `patch_dir`，然后执行 `patch_hook`（可空则跳过）。manifest 缺失（首次验证/
   节点重建）→ 退化为构建输出**文件级全量同步**一次，之后恢复增量。
4. **冒烟**：复用 CAP-10 该环境绑定的冒烟套件（无绑定则跳过并标注「未执行冒烟」）。

触发即返回 runId，全链异步；状态机 QUEUED → PUSHING/BUILDING/PATCHING/TESTING →
SUCCESS/FAILED。`commit_sha`、变更文件数、各阶段耗时落 verify_runs 追溯。

### FR-04 Agent 自主触发通道

会话上下文装配（CAP-33/34 ContextPackage）为挂需求且已绑定验证环境的会话物化
助手脚本 `.devmind/bin/devmind-verify`（平台物化路径照旧写进 worktree `info/exclude`，
不占 git 跟踪文件），内嵌**会话作用域 token**（复用 CAP-34 context 拉包 token 机制）。
agent 经 Bash 调用 `devmind-verify [--note "..."]`：

- 成功受理：输出 runId 与「结果将回灌本会话」提示，立即返回（不阻塞 agent）；
- 被拒：输出结构化错误码与引导文案（未绑定环境/环境缺补丁配置/无新提交/并发冲突/
  超限）。

skill 注入块（CLAUDE.local.md）同步广告该命令的存在与使用时机
（「完成一个可验证的改动并 commit 后，运行 devmind-verify 验证」）。

### FR-05 限额与互斥

- **需求级互斥**：同需求同时刻仅一个 verify run（QUEUED/进行中再触发 → 409），
  与 CAP-51 需求工作区互斥同口径。
- **项目级开关**：`projects.verify_in_dev_enabled`（默认关，Boolean 可空 + getter
  兜底，**不加 @ColumnDefault**——CAP-72 同款红线）；关闭时人工/agent 触发一律 403。
- **日配额**：`projects.verify_daily_quota`（整数，默认 20，0 = 不限）；
  超限 429，文案告知次日重置与人工调整入口。

### FR-06 结果回灌与通知

verify run 终态时：

- **回灌会话**：该需求有进行中会话 → 以 system 事件注入会话事件流
  （RunnerSessionRegistry 注入先例）：结果 + commit_sha 短号 + 变更文件数 +
  失败阶段与日志尾部（截断）+ 成功时的访问地址。agent 下一轮自然看到，自主决定
  修复迭代。
- **无进行中会话**：落验证历史 + CAP-06 通知需求负责人（成功带访问地址，失败带
  失败摘要与日志链接）。

### FR-07 人工入口与收口可见性

- 需求详情页新增「验证」Tab：verify run 历史列表（状态/触发人 AGENT·MANUAL/
  commit/变更文件数/耗时/失败阶段）+ 「立即验证」按钮（与 agent 触发同链同限额
  同互斥）。
- 收口确认弹窗展示**最近一次 verify run 状态**（成功绿标/失败红标/从未验证灰标），
  仅提示不门禁——「验证通过才允许收口」的硬门禁另立 CAP。

## 3. 插件化接口

无新增 SPI。编排落 **devmind-flow**（CAP-17 PipelineOrchestrator 先例：flow 已直接
依赖 devmind-build/deploy/test 实现）；会话事件注入走既有
`RunnerSessionRegistry.injectSystemEvent` 通道；agent 触发物化复用 CAP-34
ContextPackage 管线；补丁下发与钩子执行复用 CAP-36 exec 帧通道。

## 4. 依赖关系

依赖 CAP-13（Requirement 模型与需求级归属）、CAP-51（需求分支 `feature/req-<id>`
与需求级互斥口径）、CAP-08/10（构建/测试执行器）、CAP-12（执行底座与日志 Hub）、
CAP-34/36（ContextPackage 物化 + exec 帧 runner 执行通道）、CAP-26（远端引用基准）、
CAP-06（通知）、CAP-72（项目级开关先例）。**不依赖 CAP-09 部署执行器**——补丁更新
不走部署计划，是 exec 帧上的文件下发 + 命令钩子。

## 5. 数据模型

```
requirements 加列：verify_environment_id BIGINT NULL  -- environments.id

environments 加列：
  patch_dir   VARCHAR(512) NULL   -- 部署目录（补丁覆盖目标）
  patch_hook  VARCHAR(1024) NULL  -- 更新后命令（空 = 不重启）

projects 加列：
  verify_in_dev_enabled BIT NULL  -- 默认关（getter 兜底，不加 @ColumnDefault）
  verify_daily_quota    INT NULL  -- 默认 20（getter 兜底），0 = 不限

verify_runs(
  id                BIGINT PK,
  requirement_id    BIGINT NOT NULL,
  project_id        VARCHAR(32) NOT NULL,
  trigger_type      VARCHAR(8) NOT NULL,     -- AGENT / MANUAL
  triggered_by      VARCHAR(64),             -- 人工触发用户名；agent 触发为会话 id
  environment_id    BIGINT NOT NULL,
  commit_sha        VARCHAR(64),
  stage             VARCHAR(16),             -- QUEUED/PUSHING/BUILDING/PATCHING/TESTING
  status            VARCHAR(16),             -- RUNNING/SUCCESS/FAILED
  files_changed     INT,                     -- 补丁下发文件数（全量同步 = 全量文件数）
  duration_ms       BIGINT,
  failure_summary   LONGVARCHAR,             -- 失败阶段+日志尾部（脱敏截断）
  access_url        VARCHAR(512),
  created_at, finished_at
)
```

同步清单 manifest（文件路径+哈希）存**目标环境节点侧**（`patch_dir` 旁
`.devmind-verify-manifest.json`），服务端不存——节点清单丢失只导致一次全量同步，
无正确性风险。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/requirements/{id}/verify` | 人工触发（限额/互斥/开关同 agent 通道） |
| GET | `/api/requirements/{id}/verify-runs` | 验证历史（分页） |
| POST | `/api/verify/trigger?token=` | agent 通道（会话作用域 token 自证，免登录） |

需求读写 DTO 增 `verifyEnvironmentId`；环境读写 DTO 增 `patchDir`/`patchHook`。

## 7. 验收标准

1. 需求绑定已配补丁三件套的环境后，会话内 agent commit 改动并执行
   `devmind-verify`：verify run 走完 推送→增量构建→补丁下发（仅变更文件）→钩子→
   冒烟，进行中会话收到结果事件（含变更文件数与访问地址）。
2. 第二次验证只下发**增量变更文件**（manifest 比对生效）；删掉节点侧 manifest 后
   第三次退化为全量同步一次，第四次恢复增量。
3. 构建基准是需求分支**已 push 的 HEAD commit**；HEAD 与上次验证相同 = 409 拒绝
   并提示先提交；未 commit 的 worktree 改动不参与。
4. 同需求并发触发 → 409；超日配额 → 429；未绑定环境 → VERIFY_ENV_UNBOUND；
   环境缺补丁配置 → VERIFY_ENV_NOT_PATCHABLE；项目开关关闭 → 403。
5. 任一阶段失败：链终止，会话收到失败阶段 + 日志尾部，历史可查。
6. 无进行中会话时人工触发：结果走通知，需求「验证」Tab 可见完整历史。
7. 收口确认弹窗显示最近验证状态；PROD 环境不出现在绑定选项中。
8. 存量需求/环境（新列 null）行为零变化。

## 8. MVP 范围与非目标

**MVP**：FR-01~07 全量（单环境绑定、串行四步链、限额互斥、回灌与通知、验证 Tab）。

**非目标（另立 CAP）**：

- 全量构建/部署/测试链——那是 CAP-17/08/09 的既有形态，本能力不做也不替代；
- 验证通过作为收口硬门禁（本期仅可见性提示）；
- 补丁导致的环境漂移治理：验证环境被补丁改脏是**预期行为**，环境恢复靠下次正式
  全量部署（CAP-09）重置，本期不提供「还原到基线」动作；
- 同环境并发需求的部署物互相覆盖（已知限制，规避方式 = 绑不同环境）；
- 构建系统级增量优化（mvn 模块裁剪/gradle up-to-date 等，属项目构建脚本职责）；
- agent 修复迭代的轮次自治策略（靠日配额兜底，精细预算另议）；
- PROD 环境的任何自动动作（红线）。

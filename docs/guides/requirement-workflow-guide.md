# 需求处理流程指南（Jira 同步需求 → AI 修复 → 验收完结）

> 适用：CAP-13 研发主线 + CAP-14 流程引擎 + CAP-15 派发编排 + CAP-19 Jira 同步的现行口径。
> 以 REQ-13（ADMQ-8327 权限缺陷）实操为例。日期：2026-09-10

## 1. 全景图

```
Jira issue ──轮询同步──▶ Requirement(DRAFT, source=JIRA)
                            │
              ┌─────────────┴─────────────┐
              ▼ 简单需求（推荐默认走这条）   ▼ 复杂需求（AI 半自动流程）
        手工建 Work Item              更多▸开始分析 → 生成方案
              │                            → AI 拆分 → 草稿确认
              ▼                                  ▼
        行内「起会话」                    拆分固化后自动派发（depends_on DAG）
              │                                  │
              └──────────────┬───────────────────┘
                             ▼
                  agent 会话在 runner 执行（worktree 隔离）
                             ▼
                  人审 diff/产物 → WI 手工标 DONE
                             ▼
                  全部 WI DONE → 需求自动 rollup ACCEPTANCE
                             ▼
                  头卡「验收通过」→ DONE（终态）
                             ▼
            「Jira 操作」：工作流转换回写 Jira + 登记工时
```

核心原则：**AI 负责产出，人负责确认；需求状态大部分自动派生，人工只在收口处介入——WI 的 DONE、需求的最终验收（或免工作单元的「直接完成」）。**

## 2. 简单需求快速路径（默认）

适用于：标题/截图已能说清问题的 Bug、小改动。全程 4 步，**不点「更多」里的任何 AI 动作**。

### ① 建工作单元
需求详情页 → 「工作单元」Tab → **新建工作单元**：
- 类型：`DEVELOPMENT`（纯文档/测试场景选对应类型）
- 标题：一句话说清做什么
- **执行输入 spec**：写给 agent 的任务说明——起会话时自动作为 taskSpec 注入，**不填起会话会被拒**（"工作单元缺少 spec"）。
  spec 建议结构：【现象】【期望】【排查方向】【要求】，Jira key 写进去便于溯源
- 分支 slug 可留空（自动按 `wi/<seq>-<slug>` 生成）

### ② 起会话
工作单元行内 **起会话** → 确认后自动：
- 用 spec 起 agent 会话并跳转会话详情页；
- WI 自动 `TODO → IN_PROGRESS`，需求 rollup 到 `IN_PROGRESS`。
- 执行节点路由：会话请求指定节点 → 项目默认节点 → 平台默认节点，皆无则 409；节点离线同样 409 不静默起失败进程。

### ③ 审结果 → WI 标 DONE
会话跑完有站内通知。在会话详情看 diff/事件流确认改动正确，回工作单元 Tab **状态下拉翻 DONE**。
> 会话完成 **不会** 自动 DONE——「派发自动化，完成判定归人」（CAP-15 语义边界），必须人工翻。

### ④ 验收需求
全部 WI DONE → 需求自动 rollup `ACCEPTANCE` → 头卡出现 **「验收通过」** → 点击即 `DONE` 终态。

### ⑤ Jira 回写（仅 Jira 来源需求）
头卡 **「Jira 操作」** 下拉：
- 列出 issue 当前可用的工作流转换（如「解决」「关闭」），确认后回写 Jira 远端——**只改远端，不动本地状态**（两边状态机独立）；
- **登记工时**：默认带出 AI 实际耗时换算的小时数（需求属性里的「AI 执行耗时」），写进 Jira worklog。

## 3. 复杂需求 AI 流程路径（可选）

适用于：需求描述模糊、影响面大、需要方案评审的。入口全部在头卡 **「更多」** 下拉，按顺序：

| 动作 | 干什么 | 完成后 |
|---|---|---|
| 开始分析 | 起分析型会话（直挂需求），产出 `.devmind/output/analysis.md` | 通知"分析就绪"，产物列表出现 ANALYSIS |
| 生成方案（AI） | 建 DESIGN 型 WI 并起会话，产出 `design.md` → 自动登记方案文档 + Design(DRAFT) | 「方案」Tab 人工 CONFIRMED |
| AI 拆分工作单元 | 起拆分会话，产出 `wi-plan.json`（有方案须先 CONFIRMED） | 通知"拆分草稿就绪" |
| 拆分草稿 | 查看/编辑草稿 → 确认固化：批量建 WI + depends_on 边（有环整体拒绝） | **首批无依赖 WI 自动起会话**（CAP-15） |

固化后 WI 的派发是依赖驱动的：某个 WI 翻 DONE 时，编排器自动派发依赖就绪的后继 WI。执行中仍可人工建行、调状态、起会话——流程引擎只做门禁校验，不是围墙。

agent 未写产出文件时**不阻塞**：通知降级"请人工处理"，退回手工建 WI（§2 路径永远可用）。

## 4. 状态语义速查

**Requirement**（派生为主，人工三处收口）：

| 状态 | 谁来翻 |
|---|---|
| DRAFT → ANALYZING | 流程引擎（点「开始分析」） |
| → DESIGNING / IN_PROGRESS | rollup 自动（有 DESIGN 项未完成 / 有执行中 WI） |
| → ACCEPTANCE | rollup 自动（全部 WI DONE） |
| ACCEPTANCE → **DONE** | **人工**（头卡「验收通过」） |
| 任意非终态 → **DONE** | **人工**（详情页「更多」→ 直接完成 / 列表行内「完成」；伪需求免工作单元） |
| 任意 → CANCELLED | 人工（「更多」→ 取消需求） |

**Work Item**：TODO / IN_PROGRESS / BLOCKED / DONE / CANCELLED。起会话自动推 IN_PROGRESS；**DONE 只能人工**；其余状态下拉随意翻（转换路径不写死）。

## 5. 常见疑问

- **「更多」里的 AI 动作是必经步骤吗？** 不是。简单需求从建 WI 开始即可，分析/方案/拆分是给复杂需求的 AI 辅助路径。
- **伪需求/不用开发的需求怎么完结？** 不用建工作单元：列表行内点「完成」，或详情页「更多」→「直接完成」，任意非终态直接翻 DONE（不经 rollup，DONE 后状态不再被派生覆盖）。
- **Jira 需求改了 Jira 侧状态，本地会跟着变吗？** remoteStatus 随同步刷新展示，但本地需求 status 绝不动（人工接管状态机）；反向也一样，本地 DONE 不会自动回写 Jira，要走「Jira 操作」手动转换。
- **Jira 来源需求哪些字段不能改？** 标题/描述/优先级/经办人等托管字段本地只读（表单禁用 + 服务端强制），由同步维护；status/ownerId/描述外的一切本地操作（建 WI、起会话、验收）不受影响。
- **起会话报 409？** 没有可用节点（项目/平台默认节点均未配或离线），或并发超限（TOO_MANY_SESSIONS，编排器会等下一个 DONE 事件重试，不丢任务）。
- **会话跑失败了怎么办？** WI 状态不变（仍 IN_PROGRESS），排查后可再行内「起会话」重试；或手工翻回 TODO。
- **想纯记录不让 AI 干？** 建 WI 后直接状态下拉推到 DONE，再起需求验收——主线只当台账用也成立。
- **会话结束了但改动没提交？** runner 的 claude 以 `acceptEdits` 权限跑，`git add`/`git commit`/`mvn` 可能被拦（产出留在 worktree）。此时到 runner 节点的 `workspaces/<pid>/sessions/<sid>` 工作区人工验证（`mvn test`）并 commit，再回会话详情 `/finish` 收尾；远程 diff 只统计已提交内容，未 commit 时diff 为空属预期。

## 6. 本次 REQ-13 实操时间线（示例）

1. `新建工作单元` WI-1（DEVELOPMENT，spec 写清现象/期望/排查方向）
2. 行内 `起会话` → WI/需求自动 IN_PROGRESS（首次会话因 runner 环境故障 FAILED，重起即可，WI 状态不变）
3. 会话跑完（WAITING_INPUT）→ 读 result 消息看根因报告 → 到 worktree 跑 `mvn -pl admq-user test`（951 全绿）→ 人工 commit（agent 权限被拦）→ `/finish` 会话 DONE
4. WI-1 翻 `DONE` → 需求自动 rollup `ACCEPTANCE` → 头卡 `验收通过` → `DONE`
5. `Jira 操作` → 执行转换回写 Jira + 登记工时（默认 = AI 耗时，本次 1.6h）

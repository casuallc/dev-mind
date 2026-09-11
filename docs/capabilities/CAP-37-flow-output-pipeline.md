# CAP-37 流程产出回传与阶段串联

> 能力 ID：CAP-37 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-09-11
>
> ⚠️ 后续调整（CAP-38）：FR-04 的「流程」Tab 已删除，改为分析/方案两个独立阶段 Tab；
> 拆分在方案产出后自动执行且 wi-plan.json 自动固化为正式 WI（CAP-14 FR-06/07 的
> 人工确认草稿环节与 split-draft/confirm-split 端点取消）。FR-01/02/03 的产出回传、
> 分析文档化、spec 注入机制保持不变并被 CAP-38 复用。

## 1. 目的

修复 CAP-14 需求流程在 CAP-34（服务端零执行）后的**断链**，并把「分析 → 方案 → 拆分 → 执行」串成一条前后继承的链路：

1. **产出回读链路已失效**：`RequirementFlowService.readOutput` 靠 `sessions.worktree_path` 读
   `.devmind/output/` 约定文件，CAP-34 后该字段恒为 null（本机时代遗留），产出实际写在
   runner 侧工作区且从不回传——分析产物从不登记、方案文档/Design(DRAFT) 从不自动创建、
   拆分草稿永远为空。
2. **阶段间上下文不传递**：方案会话 spec 只塞需求描述，分析结论不进入；拆分会话仅在有
   CONFIRMED 方案时注入方案，无方案时也不注入分析——每个阶段都是从零开始的新会话。
3. **UI 不呈现产出**：分析产物无任何内容预览；流程动作散落在「更多」下拉，阶段推进感弱。

核心原则不变（CAP-14）：**AI 负责产出，人负责确认；流程引擎负责推进状态与登记产物。**
本能力补的是「产出从 runner 回到服务端并沉淀为平台资产」与「上游产出注入下游会话」两环。

## 2. 功能需求

- **FR-01 产出回传通道**：runner 在会话进程退出后、工作区清理（finalizer）**之前**，
  扫描会话目录 `.devmind/output/`（一层、仅常规文件），同步 HTTP POST 上传到
  `POST /api/agent/output/{sessionId}?token=<节点token>`；服务端校验节点 token 后经
  `SessionOutputSink` SPI 落 `session_outputs` 表（同 sessionId+fileName 覆盖）。
  上传失败不阻塞 exit 帧（log + system 事件告知）；老 runner 不上传时服务端走既有降级通知。
  纯 HTTP 旁路通道，AgentProtocol 不升版。限制：单文件 ≤1MB、文件数 ≤16、总量 ≤4MB、
  文件名白名单 `[A-Za-z0-9._-]{1,64}`。
- **FR-02 分析产出文档化**：docs 新增 `kind=analysis`；分析会话 DONE 且产出存在时，
  流程引擎把 analysis.md 落成该需求的分析文档（**重新分析走既有文档版本化**，不新建第二份），
  ANALYSIS 产物 ref 由本地文件路径改为 docId；通知指向「流程」Tab。
- **FR-03 阶段上下文串联**：
  - 方案会话 spec 注入该需求最近一次分析文档内容（「需求分析结论」节，无则省略）；
  - 拆分会话 spec：有 CONFIRMED 方案时方案为主 + 分析作背景节（截断 ~4000 字符防膨胀）；
    无方案时注入分析全文；皆无保持现状；
  - 执行会话（WI）不额外注入——WI.spec 由拆分会话参考上游产出生成，应当自足。
- **FR-04 流程 Tab 与通知深链**：需求详情页新增「流程」Tab，纵向聚合四阶段
  （①分析：状态 + 开始/重新分析 + 查看分析；②方案：生成方案 + 确认/废弃/查看；
  ③拆分：AI 拆分 + 拆分草稿；④执行：工作单元完成度）；「更多」下拉移除流程动作只留
  生命周期操作；`flow.*` 类型通知点击「查看」深链到 `?tab=flow`。

## 3. 插件化接口

- 新增 SPI：`devmind-common` 的 `SessionOutputSink`（store(sessionId, files)），
  devmind-session 实现落库，devmind-agent 上传端点以 `ObjectProvider` 探测注入——
  与 `ContextPackageProvider` 同构，模块依赖方向不变（agent→common←session）。
- flow 直接注入 devmind-session 的 `SessionOutputService.findContent`（pom 已有依赖），
  输出契约路径常量仍集中在 `FlowOutputContract`，回传后**文件不再依赖 worktree 路径**。

## 4. 依赖关系

- 依赖：CAP-14（流程引擎与输出契约）、CAP-34（runner 执行与 HTTP 通道先例）、
  CAP-03（docs 文档化与版本化）、CAP-13（产物登记）。
- 被依赖：无新增；CAP-15/17 编排链路自动受益（拆分草稿/方案登记恢复可用）。

## 5. 数据模型

- 新表 `session_outputs`：`id` 自增、`session_id`、`file_name`、`content`
  （@Lob LONGVARCHAR，16M）、`created_at`；`session_id + file_name` 唯一约束。
  随会话生命周期存在（会话删除暂不级联清理，量小，后续 GC 再说）。
- docs 表无结构变更，仅 kind 枚举扩 `analysis`。

## 6. API 概要

```
POST /api/agent/output/{sessionId}?token=    runner 产出上传（节点 token，permitAll）
body: {files: [{name, content}]}
```

其余均为既有端点行为修复（flow/analyze、flow/design、flow/split、split-draft 的输入
组装与产出读取），无新前端 API。

## 7. 验收标准

- 分析会话 DONE 后：session_outputs 有 analysis.md 行；需求出现 kind=analysis 文档
  （重新分析版本累加不新建）；产物列表 ANALYSIS 行 ref=docId；通知 flow.analysis.ready
  点击直达「流程」Tab；
- 方案会话 DONE 后自动登记 design 文档 + Design(DRAFT)（CAP-14 原验收恢复）；
  方案会话的 taskSpec 含最近分析结论；
- 拆分会话 taskSpec 在有方案时含方案+分析、无方案有分析时含分析；拆分草稿可从
  session_outputs 解析并正常固化；
- 老版本 runner（不上传产出）行为与现状一致（降级通知，不报错）；
- 「更多」下拉不再含流程动作；流程 Tab 四阶段状态/产出/动作可用。

## 8. MVP 范围（暂不做）

产出文件的版本历史（session_outputs 只留最新）、会话删除时产出级联清理、
产出大小超限的截断上传、执行会话注入上游产出、split 草稿落库（仍沿 CAP-14 不落库）。

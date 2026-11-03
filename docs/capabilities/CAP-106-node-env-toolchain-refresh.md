# CAP-106 节点环境变量与工具链探测刷新

> 能力 ID：CAP-106 ｜ 分类：底座 ｜ 状态：已交付（M1+M2，2026-11-03） ｜ 日期：2026-11-03
> 决策记录：立项动机 = 2026-11-03 194 环境事故——Kafka POC 项目程序型用例选 TEST 环境
> （绑定节点 6 apusic-224）被执行前预检 BLOCKED：「节点 6 未探测到 java 运行时」。实查 224
> 装有 JDK 21.0.7（runner 自己都用它跑），但 systemd 服务 PATH 不含 JDK bin，runner 启动期
> 一次性 PATH 探测失败 → hello 快照无 java → 预检验死。事故暴露三个平台洞：①toolchain 只在
> runner 启动时探测一次，机器后装运行时永不感知，必须重启 runner；②预检只看 hello 快照，
> 界面可配的环境变量（CAP-104 环境 variables → exec 注入）不参与判定，「PATH 覆盖」合法场景
> 被误杀；③节点级环境变量（PATH/JAVA_HOME 这类机器差异）没有平台管理入口。定稿方向：
> **节点 env 界面化（服务端持有、下发合并）+ runner 周期重探测随心跳刷新 + 显式工具链路径
> 回落**；预检保持 fail-fast 语义不降级（否决「改警告放行」），快照缺失但注入 env 含 PATH
> 覆盖时把最终判定下沉到 runner 执行期探测（仍不跑入口脚本，BLOCKED 语义不变）。

## 1. 目的

节点机器的运行时环境（JDK 装在哪、PATH 怎么配）今天是「登机器改 provisioning」的黑盒：
平台既看不见也管不了，且 runner 对运行时的感知停留在启动那一刻。本能力把这件事收进平台：

- 节点级环境变量界面可配（PATH/JAVA_HOME/自定义变量），随 exec/proc/program 帧下发合并，
  机器差异不再要求登机器改 systemd/shell 配置；
- runner 周期重探测工具链并随心跳上报，「后装 java」分钟级自愈，不再必须重启 runner；
- agent.properties 支持显式工具链路径声明，PATH 探测不到时回落显式路径，版本号照常上报；
- CAP-104 M1.5 预检与注入 env 联动：注入 env 显式覆盖 PATH 时不再被陈旧快照误杀，
  最终判定下沉 runner 执行期（合并 env 后探测，不满足仍 BLOCKED、不跑入口）。

## 2. 功能需求

### 节点环境变量与探测刷新（M1）

- **FR-01 节点级环境变量**：`agent_nodes` 新增 `env_json`（TEXT 可空，JSON 对象
  `{"KEY":"VALUE"}`）；节点页编辑（KEY=VALUE 文本域，对齐环境变量编辑交互）。key 校验
  `[A-Za-z_][A-Za-z0-9_]*`，空值即删除该 key。
- **FR-02 下发合并语义**：exec / proc / program（LabBundleRef 路径）帧 env 合并优先级
  （低→高）：**节点 env < 测试环境 variables < 用例/步骤 env**。节点 env 只作基底，
  不覆盖业务侧显式传值。runner 侧无需改合并逻辑（帧内 env 已是终态，服务端合并完下发）。
- **FR-03 周期重探测**：runner 启动探测保留，另加定时重探测（默认 10 分钟，
  `toolchainRefreshMinutes` 可配，0=关闭）；结果与上次**有变化**才随下一次 heartbeat
  携带 `toolchain` 字段上报（无变化不带，心跳不膨胀）。
- **FR-04 服务端心跳刷新**：`AgentNodeWsHandler` 识别 heartbeat 帧的 `toolchain` 对象，
  非空即覆盖落库 `agent_nodes.toolchain`（与 hello 同一写入路径）；帧字段向后兼容，
  旧 runner 不带 = 维持现状口径，**不 bump 协议版本**。
- **FR-05 显式工具链路径**：agent.properties 支持 `toolchain.<key>=/path/to/bin`
  （如 `toolchain.java=/home/jdk-21.0.7+6/bin/java`）；ToolchainDetector 对该 key 先用
  显式路径执行 `-version` 探测（解析正则不变），失败再回落 PATH 探测；显式路径探测成功
  的版本覆盖 PATH 探测结果。key 取值限于既有探测目标集（java/mvn/node/npm/docker/git）。

### 预检联动（M2）

- **FR-06 预检 env 联动**：`AgentNodeRouter.requireProgramCompatible` 在 toolchain 快照缺
  所需 key 时，检查本次执行合并后 env（节点 env + 环境 variables + 用例 env）是否含 `PATH`
  （或该 key 对应的 `*_HOME`，java→JAVA_HOME）覆盖：无覆盖 → 维持现状 BLOCKED；有覆盖 →
  放行并把判定下沉 runner。
- **FR-07 runner 执行期探测**：runner 解包程序包后、跑入口脚本前，按**合并后 env** 对
  manifest.requiredToolchainKey() 做一次执行期探测（复用 ToolchainDetector 单目标探测，
  ProcessBuilder 注入合并 env）；不满足 → 以 BLOCKED 语义回报（不跑入口，日志带「执行期
  探测 java 失败：最终 PATH=…」），满足 → 正常执行。保持 CAP-104「不跑到一半才炸」意图。
- **FR-08 快照可信度标注**：节点页 toolchain 展示附「上报时间」（取 last_heartbeat_at）
  与来源徽标（启动探测 / 周期刷新 / 显式声明），预检 BLOCKED 文案附「若刚安装运行时，
  等一个探测周期或重启 runner」指引。

## 3. 否决方案

- **改机器 provisioning（systemd drop-in / profile.d 加 PATH）**：本次事故的应急解锁手段
  （已在 224 实施），本身正当但不可平台化管理——每台机器一个故事，平台看不见也验不了。
- **预检降级为警告/取消 toolchain 门**：否决。丢失 fail-fast 价值，违背 CAP-104 M1.5
  「执行前 BLOCKED 而不是跑到一半报命令不存在」的设计意图。
- **服务端见 PATH 覆盖即乐观放行（不做执行期探测）**：否决。服务端不知道 PATH 里真实
  有没有 java，会把真缺运行时的执行放进场内烂尾；FR-07 的下沉探测是必要兜底。
- **节点 env 存 runner 侧 agent.properties**：否决。与「界面可管」目标冲突；runner 配置
  文件仍是合法覆盖手段（FR-05 只覆盖工具链路径这一窄域），通用 env 归平台管。
- **心跳恒带 toolchain**：否决。心跳 15s 一次，无变化时带同一份 JSON 是纯膨胀；
  变化才带 + hello 恒带足够覆盖。

## 4. 架构与模块归属

- `devmind-agent`：agent_nodes.env_json 实体/编辑端点扩展；heartbeat toolchain 落库；
  节点 env 经 `AgentNodeConnector` SPI 新增 `nodeEnv(nodeId)` 暴露（默认空表）。
- `devmind-execution`：`AgentNodeRouter.requireProgramCompatible` FR-06 联动；
  exec 下发处合并节点 env 基底（FR-02）。
- `devmind-test`：`CaseExecutionService.commandEnv` 合并链加节点 env 基底（经 connector，
  不反向依赖 agent 实现）。
- `devmind-agent-runner`：周期重探测 + heartbeat 携带（FR-03）；`toolchain.<key>` 配置
  解析（FR-05）；程序包执行期探测（FR-07，ExecHandler 程序包路径）。
- `frontend/src/features/<节点页>`：env 编辑 + toolchain 来源徽标（FR-01/FR-08）。
- 依赖方向不变：execution/test → common SPI ← agent；runner 只认协议帧。

## 5. 数据模型

```sql
-- agent_nodes 新增（ddl-auto=update 自动加列，可空无默认，存量库无需手工动作）
env_json  TEXT  NULL   -- {"PATH":"...","JAVA_HOME":"..."}；NULL=未配置
```

复用既有列：`agent_nodes.toolchain`（hello/heartbeat 上报落库，本能力新增 heartbeat 写入路径）。

## 6. API 概要

- 复用 `PUT /api/agent-nodes/{id}`：请求体增 `env` 对象（null=不动，{}=清空）。
- 复用 `GET /api/agent-nodes` 视图：`AgentNodeView` 增 `env`（明文；节点 env 定位是
  机器配置不是凭证，**禁存密钥**，UI 文案明示）。
- 协议帧（ws/agent，不改版本号）：heartbeat 帧可选增 `toolchain` 对象；exec/proc 帧
  env 字段语义不变（服务端完成合并）。

## 7. 安全

- 节点 env 明文落库明文展示，编辑 UI 与 CAP 文档双重明示「禁存密钥」；密钥仍走
  测试环境 secrets（加密列）链路。
- env key 白名单正则防注入（`=`、换行、空格不进 key）；value 长度限 4KB/条、64 条/节点。
- PATH 类覆盖只影响该节点执行环境，不影响服务端；runner 本就全权控制节点进程环境，
  不扩大暴露面。

## 8. 里程碑

- **M1 节点 env + 探测自愈**（已交付）：FR-01 / FR-02 / FR-03 / FR-04 / FR-05。
  交付后「界面给节点配 PATH 即生效执行」+「后装 java 分钟级自动感知」。
- **M2 预检联动**（已交付）：FR-06 / FR-07 / FR-08（FR-08 按 O2 口径只做了快照时间提示，
  来源徽标砍）。交付后 PATH 覆盖场景不被陈旧快照误杀，且仍不跑半截脚本。

## 9. 验收标准

- M1：
  - 单测：ToolchainDetector 显式路径回落/覆盖；heartbeat toolchain 落库；env 合并优先级。
  - E2E（fake 节点）：配节点 env → exec 帧 env 含基底值且被用例 env 正确覆盖；
    runner 探测结果变化后一个心跳周期内节点页 toolchain 刷新。
  - 真机回归：224 场景复现——删掉 systemd drop-in、改配节点 env PATH，程序型用例
    从 BLOCKED 转 SUCCESS。
- M2：
  - 单测：requireProgramCompatible 三分支（快照有/快照无+无覆盖 BLOCKED/快照无+有覆盖放行）。
  - E2E：toolchain 无 java + 节点 env 配 PATH → 用例 SUCCESS（执行期探测过）；
    节点 env 配假 PATH → 执行期探测失败，用例 BLOCKED 且入口脚本未执行（工作区无产出）。

## 10. 风险与开放问题

| 编号 | 内容 | 口径 |
|---|---|---|
| R1 | 周期探测每 10 分钟起 6 个子进程，低配节点有微小开销 | 接受（各 5s 超时、串行、可关） |
| R2 | heartbeat 刷新与 hello 覆盖竞态（重连瞬间旧快照回写） | 缓解：同一连接串行帧，hello 恒带最新探测 |
| R3 | 节点 env 被当密钥库误用 | 缓解：UI 文案 + 文档明示；不排除未来加 secret 引用语法 |
| O1 | proc 帧（CAP-57 边车管控）是否同吃节点 env | 待决：M1 实现时确认 ProcHandler env 链，口径与 exec 对齐 |
| O2 | toolchain 来源徽标的「显式声明」识别需 hello 增字段 | 待决：M1 只做上报时间，来源徽标可砍 |

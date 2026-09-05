# CAP-25 远程会话工作区编排（Runner 侧代码生命周期）

> 能力 ID：CAP-25 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-05

## 1. 目的

CAP-21 定稿时明确「平台不做代码同步，worktree 由节点机自理」（FR-06 + MVP 暂不做）。
实际部署形态（Linux 服务端 + Windows runner 节点）暴露了缺口：远程会话的 workdir
靠节点机**手工 clone + 手工配 `project.<id>` 映射 + 手工维护凭据**，且会话前不同步基线、
会话后改动不回远端——agent 改的代码游离在平台视野之外，服务端 CAP-08/11/18 全部消费不到。

本能力把**工作区生命周期下沉到 runner**（与 CAP-23「克隆后消费方零改动受益」同一思路，
方向相反）：launch 帧携带 repo 描述 + 短期凭据，runner 自动完成
clone（首次）→ fetch → 切会话分支（每会话独立 worktree）→ 会话结束 push 回远端。
节点机零手工配置：装 runner、配 serverUrl+token 即可。

**事实源认知**：GitLab/GitHub 远端是唯一事实源；服务端 clone（CAP-23）与节点工作区
都是普通克隆，二者不直接同步，代码流转全部经过远端。

```
服务端 launch{repo{remoteUrl,baseBranch,branch,token}}
   → runner: ensureClone(workspaceRoot/<projectId>/main)
   → fetch baseBranch → worktree add -b feature/<sid> sessions/<sid>
   → claude 在会话 worktree 内开发（commit 自由，push 由平台接管）
   → 进程退出 → push feature/<sid> → exit 帧带 pushed/branch/pushError
```

## 2. 产品决策（已定稿）

1. **每会话独立 worktree**：节点上同一项目允许并发会话（maxConcurrent），共享一份
   克隆缓存 `<workspaceRoot>/<projectId>/main`，每会话
   `git worktree add` 到 `<workspaceRoot>/<projectId>/sessions/<sessionId>`，
   分支 `feature/<sessionId>`（与服务端 WorktreeManager 同约定，**分支名由服务端
   在 launch 帧下发**，runner 不复制命名逻辑）。
2. **凭据随帧下发、仅存内存**：服务端按 CAP-24 优先级解析 token（会话发起人个人 PAT
   （remoteUrl host 匹配）→ 项目绑定 Integration），随 launch 帧 `repo.token` 下发；
   runner 以 sessionId 为键存内存，git 进程一律显式 URL 内嵌注入（CAP-23 同款
   `withToken`），**clone 后立即 `remote set-url origin <cleanUrl>` 防残留**；
   会话结束（push 完）即弃。token 不落盘、不进日志（输出统一脱敏）。
3. **agent 会话内不自行 push**：节点 git 配置无凭据，claude 自行 `git push` 会失败——
   这是有意设计：push 收敛到会话结束单点（runner 执行），避免半成品分支上远端。
   agent 只需 commit；未 commit 的改动不推送（push 只推提交）。
4. **push 是 best-effort 安全网**：进程退出（含被杀）即尝试 push；
   分支无新提交（`everything up-to-date`）视为成功 no-op；push 失败**不反转会话结局**，
   经 exit 帧 `pushed/pushError` 上报告知。
5. **优雅降级**：launch 帧无 repo 块（老服务端 / 项目无 remoteUrl / token 解析失败）
   → 回退现有 `project.<id>` 映射 + 兜底 workDir 行为，与 CAP-21 现状完全一致。
   老 runner 收到 repo 块：JsonNode 逐字段读取，未知字段天然忽略 → 行为不变。
6. **顺带修复**：`AgentConnectionRegistry.launch()` 组帧漏发 `env`（CAP-24 提交身份
   对远程会话静默失效，runner 侧读取逻辑已在等该字段）——本能力同一代码路径，一并修。

## 3. 功能需求

- **FR-01 launch 协议扩展（服务端）**：`AgentLaunchCommand` 增加
  `RepoSpec(remoteUrl, baseBranch, branch, token)` 可空字段；组帧时序列化为
  `repo{remoteUrl,baseBranch,branch,token}`；`env` 字段补发（修复，见决策 6）。
- **FR-02 repo 块组装（服务端）**：远程会话且项目主库 `remoteUrl` 非空时：
  `branch = feature/<sessionId>`，`baseBranch = 会话请求 > 项目默认`，token 经新增
  SPI `RepoCredentialResolver`（devmind-common 定义，devmind-integration 实现，
  ObjectProvider 探测注入）解析：个人 PAT（CAP-24 host 匹配）→ 项目绑定 Integration
  token。remoteUrl 为空或 token 解析不到 → 省略 repo 块（降级，决策 5）+ warn 日志。
- **FR-03 runner 托管工作区**：配置新增 `workspaceRoot`（默认 `./workspaces`，相对
  runner 启动目录）。收到带 repo 块的 launch：
  1. 克隆缓存 `<workspaceRoot>/<projectId>/main` 不存在 → `git clone`（token 内嵌
     URL）→ 立即 `remote set-url origin <cleanUrl>`；
  2. `git fetch <urlWithToken> <baseBranch>`（显式 URL，不依赖 origin 凭据）；
  3. `git worktree add <workspaceRoot>/<projectId>/sessions/<sessionId> -b <branch> FETCH_HEAD`
     （分支已存在则先 `-D`：同 sessionId 重发 launch 的幂等保障）；
  4. workdir = 会话 worktree，拉起 claude。
  任一步失败 → 回 `launched{ok:false,error}`（复用现有 ack 通道，服务端创建失败 409/500）。
- **FR-04 结束 push 与清理**：进程退出收口（`RunnerSessionRegistry.onProcessEnd`）时，
  有 repo 块的会话：先 push（`git push <urlWithToken> <branch>:<branch>`，up-to-date
  算成功），再 `git worktree remove --force` 清理会话目录（本地分支保留在克隆缓存中，
  供事后追溯；远端分支是交付物），最后发 exit 帧（扩展 `pushed/branch/pushError` 字段）。
- **FR-05 凭据安全红线**：token 仅存在于 launch 帧（生产应 wss）与 runner 内存；
  git 输出经 sanitize（token 明文 + URL 编码形态 → `***`）后才进 runner 日志/上行帧；
  `.git/config`、磁盘任何文件不得出现 token；launch 帧服务端侧不进日志。
- **FR-06 服务端记录**：远程会话的节点侧 workdir 路径与 push 结果写入会话事件流
  （SYSTEM 类事件）与服务端日志；`sessions` 表不加列（分支名可由 sessionId 确定性推出）。

## 4. 校验规则

- `remoteUrl` 仅 http/https（沿用 GitRemoteOps 约束，ssh 报错——报错发生在服务端组装期，
  直接降级省略 repo 块 + warn）；
- runner 侧 `workspaceRoot` 下路径 normalize 后必须 startsWith(workspaceRoot)
  （projectId 来自服务端，防 `..` 逃逸；projectId 字符白名单 `[a-zA-Z0-9._-]`）；
- 会话分支名必须 `feature/` 前缀（防服务端下任意 ref 被 worktree -b 执行）。

## 5. 安全约束（沿用 CAP-18/23 并新增）

- PAT 服务端加密落库、解密仅内存、随帧下发后 runner 侧仅内存（决策 2）；
- **新增**：launch 帧含 token，WS 明文传输风险由部署侧负责（内网/wss），文档明示；
- runner 日志脱敏（决策 2）；runner 进程崩溃重启后 token 即失——崩溃时未 push 的
  会话分支留在节点克隆缓存中，人工 `git push` 恢复（文档说明）。

## 6. 数据模型与配置

- 服务端：`sessions` 表无变更。launch 帧协议见上（`repo` 块 + 补发 `env`）。
- runner `agent.properties` 新增：`workspaceRoot=./workspaces`（可缺省）。
- exit 帧扩展：`{type:"exit", sessionId, code, pushed?, branch?, pushError?}`
  （老服务端忽略多余字段）。

## 7. 模块归属与依赖

- **SPI 定义（devmind-common）**：`RepoCredentialResolver`（按 actor + repoHost + projectId
  解析 push token）；`AgentLaunchCommand` 增 `RepoSpec`。
- **服务端**：devmind-session（组装 repo 块、记录结果）；devmind-agent（组帧/exit 帧透传）；
  devmind-integration（`RepoCredentialResolver` 实现，复用 CAP-24 个人凭证 +
  项目绑定解析，token 不出模块边界——只经 SPI 返回值随帧下发）。
- **runner**：devmind-agent-runner（`RunnerWorkspace` 新类承载 clone/fetch/worktree/push，
  git 操作自实现轻量封装——runner 不依赖 devmind-integration）。
- 依赖：CAP-21（节点通道）、CAP-23（克隆/凭据注入模式）、CAP-24（个人凭证优先级）。

## 8. 验收标准

- 全新 Windows 节点（仅装 JRE + runner，无手工 clone）：创建远程会话自动完成
  clone→fetch→worktree→拉起，浏览器看事件流与本地会话一致；
- 会话中 agent commit 后退出，远端 GitLab 出现 `feature/<sid>` 分支且内容正确；
  无提交时 push 为 no-op 不报错；
- 同项目两个并发远程会话各有独立 worktree 互不干扰；
- 节点克隆缓存 `.git/config` 的 `remote.origin.url` 不含 token；runner.log 与
  服务端日志全程无 token 明文；
- push 失败（如 token 过期）会话结局不反转，事件流可见 pushError；
- 老 runner + 新服务端、新 runner + 老服务端、无 remoteUrl 项目三种降级路径行为同现状；
- CAP-24 提交身份 env 对远程会话生效（修复后 git log 署名为会话发起人）。

## 9. 暂不做

agent 会话内自行 push（需节点侧凭据落盘，违背决策 2/3）、wss 强制、节点崩溃后未 push
分支的自动恢复、远程会话 diff 视图（服务端读不到节点 FS）、多库项目的多 repo 下发
（MVP 仅主库）、runner 工作区磁盘配额/定时清理。

# CAP-73 需求开发中验证（需求分支增量补丁 → 既有环境快速更新 → 冒烟 → 结果回灌会话）

> 能力 ID：CAP-73 ｜ 分类：流程层 ｜ 状态：实施中 v4（2026-10-11，MVP 后端+runner 全链已落地；本节标注的 ⚠️ 条目为落地与 v3 草案的差异口径，以 ⚠️ 为准）
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
| 构建对象 | **push 后的需求分支远端 HEAD**（`feature/req-<id>`），不支持未提交改动 |
| 构建工作区 | **独立 verify 工作区**（`worktrees/verify-req-<id>`），不复用会话工作树 |
| 产物形态 | **`output_dir` = 与 `patch_dir` 同构的完整部署物树**，组装是项目构建脚本职责 |
| 编译范围 | **AI 判断**（`devmind-verify --modules` 透传，平台不解释语义）；空 = 全量 |
| 更新范围 | **永远由 manifest diff 决定**，AI 与配置都不插手文件清单 |
| 产物传输 | **runner 反向 HTTP 拉取/上传**（节点 token），不经 exec 帧（无字节通道） |
| 触发权 | **agent 自主触发**，平台配限额兜底；同时保留人工触发入口 |
| 工作单元 | 验证是**需求级**动作，需求下 WI 共享同一验证链与历史 |

不变量：**PROD 环境永远不可作为验证绑定目标**（CAP-17 红线不松动——生产部署永远人工）。

## 2. 功能需求

### FR-01 需求验证环境绑定

`requirements` 表加 `verify_environment_id`（可空，引用 environments 表 id；
存量行 null = 未绑定）。需求新建/编辑表单增加「验证环境」选择器，选项 = 该项目
environments 中**除 PROD 外**的全部环境（bug 类需求绑复现所在环境，功能类需求一般
绑 DEV/TEST）。**服务端校验环境与需求同 projectId**（不只靠前端选项过滤）。

未绑定 = 验证功能不可用：人工按钮置灰带引导，agent 触发返回结构化错误
（`VERIFY_ENV_UNBOUND`，文案引导先绑定环境），不静默降级。绑定的环境被删除后
（悬空引用）触发时同样按 `VERIFY_ENV_UNBOUND` 处理并提示重新绑定，不自动置空列。

### FR-02 环境快速更新配置

补丁验证要求环境**已有运行中的服务**且平台知道怎么更新它。environments 增补丁配置
（可空三件套，`patch_dir` 必填、钩子可空；缺 `patch_dir` = 该环境不可用于验证）：

- `patch_dir`：服务在该环境节点上的部署目录（补丁覆盖的目标）；
- `patch_pre_hook`：**覆盖文件前**执行的命令（停服 / systemctl stop / 备份现场；
  空 = 直接覆盖）；
- `patch_post_hook`：**覆盖文件后**执行的命令（启服 / systemctl start / 热加载脚本；
  空 = 仅覆盖文件不动进程，适用于热加载场景）；
- 执行节点取环境既有 `nodeIds` 首节点（CAP-36 口径），**verify 只补丁首节点**
  （多节点部署的其余节点不被补丁，冒烟 baseUrl 也走首节点，两者口径一致）。

**产物同构约定**（与 FR-03 步骤 2 呼应）：`patch_dir` 的内容形态必须等于构建配置
`output_dir` 产出的内容形态——平台做的是两棵树之间的文件级 diff 同步，不提供
「产物 → 部署布局」映射机制。前端 `dist` → nginx root 天然同构；Java 服务由项目
build_steps 负责把产物**组装**成部署形态（见 FR-03 步骤 2 示例）。

Windows 环境节点提示：进程运行中覆盖 jar/dll 会被文件锁挡住，「仅 post_hook 热加载」
形态在 Windows 节点基本不可用，须用 pre_hook 停服。

绑定时不强校验；触发时缺 `patch_dir` 报 `VERIFY_ENV_NOT_PATCHABLE` 并引导到环境配置页。

### FR-03 增量验证链（verify run）

新实体 `verify_runs` 承载一次验证的追溯。链分四步，任一步失败即终态
（FAILED + 失败阶段），不推进下一步（PATCHING 的 post_hook 恢复例外，见步骤 3）。

**触发前置校验**（按序，先先生效）：项目开关关闭 403 → 未绑定环境 `VERIFY_ENV_UNBOUND`
→ 缺 `patch_dir` `VERIFY_ENV_NOT_PATCHABLE` → 缺构建产物配置 `VERIFY_BUILD_NO_OUTPUT`
→ 多库项目 `VERIFY_MULTI_REPO_UNSUPPORTED`（MVP 限制，见 §8）→ 需求互斥 409 →
日配额 429 → 无新提交 409（见步骤 1）。

**链路涉及三类节点**（可同机也可不同机，编排按节点 id 寻址，不做同机假设）：

- **会话节点**：需求工作区 `worktrees/req-<id>` 所在 runner（该需求进行中/最近会话的
  agent_node_id）；
- **构建节点**：`build_configs.executor=AGENT` 时取其 `agentNodeId`（空走路由链：
  项目默认节点 → 平台默认节点）；⚠️ **v1 仅支持 executor=AGENT**（服务端本地构建腿
  未实现——CAP-34 已废本机执行，服务端零执行原则下本地构建腿亦无必要；配置为
  LOCAL 时触发按不支持处理）；
- **环境节点**：环境 `nodeIds` 首节点。

**阶段容错总则**：每阶段有默认超时（PUSH 5min / BUILD 30min / PATCH 10min / TEST 走
CAP-10 既有口径，平台配置可调）；执行中节点心跳丢失 → 该阶段判 FAILED、run 终态、
释放需求互斥，不悬挂 RUNNING。

#### 步骤 1 推送需求分支（PUSHING）

构建基准 = **远端 `feature/req-<id>` HEAD**。会话节点存在且在线时，经 ⚠️ **协议 v22 专用
`verify_push` 帧**（非通用 exec 帧——push 语义固定，runner 侧 `VerifyHandler.handlePush` →
`RunnerWorkspace.verifyPush` 显式 ref 推送）在需求工作区执行 `git push origin
feature/req-<id>`（凭证环境同 CAP-42 收口 push；push 只发已提交内容，天然满足「不支持
未提交改动」）。push 失败（agent 切了分支/远端拒绝等）→ run FAILED（PUSHING）错误输出回灌。

降级与拒绝口径（不静默验错代码）：

- 该需求**有**进行中/历史会话工作区但会话节点离线或 exec 超时 → 409
  `VERIFY_NODE_OFFLINE`（可能本地有未推送提交，禁止静默以远端 HEAD 为准）；
- 该需求**从无**会话工作区（纯远端开发）→ 跳过 push，run 上记录
  `push_status=SKIPPED`，随结果回灌可见；
- 远端无分支且本地工作区也无 → 409 `VERIFY_NO_BRANCH`。

无新提交判定：远端 HEAD 与**最近一次 SUCCESS** 的 verify run `commit_sha` 相同 →
409 `VERIFY_NO_NEW_COMMIT` 提示先提交新改动。上次 run 为 FAILED（构建/补丁/冒烟
失败修复环境后重试）**不拒绝**；人工通道另支持 `force=true` 跳过该校验。
远端 HEAD 探测在**触发请求路径**同步执行：服务端 JGit `ls-remote`（凭证走 CAP-42
同套），仓库不可达 → 409 `VERIFY_REPO_UNREACHABLE`，不吞成「无新提交」。

#### 步骤 2 增量构建（BUILDING）

构建节点上使用**独立 verify 工作区**（⚠️ runner 侧布局 `<workspaceRoot>/<projectId>/verify/<workspaceKey>`，
经 `verify_pack` 帧下发、runner `RunnerWorkspace.prepareVerify` 物化）——
CAP-51 同姿势物化，与会话工作树并列、互不影响 agent 现场（会话工作树含未提交改动，
绝不在其中构建）：

1. 工作区不存在 → 按 CAP-51 同姿势建 clone；存在则复用（增量收益来自工作区/依赖
   缓存复用，构建系统自身的增量编译是项目脚本职责）；
2. `git fetch origin` → `git reset --hard <commitSha>`（服务端 `ls-remote` 探测到的远端
   HEAD SHA 显式下发）→ `git clean -fd`（⚠️ 不带 `-x`——gitignore 的产物目录
   （如 `build/verify-out/`）必须留存，持久产物树增量组装才有意义；checkout 前
   reset/clean 兜底语义不变）；
3. 执行项目 `build_steps`（CAP-08 配置，**复用配置但不起 BuildEntity、不进 CAP-08
   构建历史**——verify 构建是验证链内部步骤，追溯落 verify_runs）。触发带的
   `--modules` 值经环境变量 **`VERIFY_MODULES`** 原样透传进 build_steps 执行环境
   （平台不解释语义；空 = 全量构建）；
4. 产物目录 = `build_configs.output_dir`（**新增列**，相对工作区根；空 = 触发时
   `VERIFY_BUILD_NO_OUTPUT` 引导到构建配置页）。

**`output_dir` 语义（红线）**：必须是**与 `patch_dir` 同构的完整部署物树**——
即使本次只编了部分模块，树中也必须包含全部部署文件（缺的会命中删除语义被从
环境删掉）。实现靠两点，均为项目构建脚本职责，平台零新机制：

- **持久产物树增量组装**：`output_dir` 指向 verify 工作区内一个 gitignore 的持久目录
  （如 `build/verify-out/`），构建脚本把本次编出的产物**覆盖拷贝**进去，未重编的
  模块产物沿用上次留存——manifest 永远对完整树生成，部分构建不产生误删；
- **可复现构建消哈希抖动**：jar/zip 类产物默认内嵌时间戳，未改动模块重编后 sha256
  也变 → 全量重传。项目侧配 reproducible build（Maven = 一行
  `<project.build.outputTimestamp>`）后，未改动模块哈希稳定，manifest diff 才真正
  只传变更文件。不配不错（只是慢），文档指南指路。

Java 服务 build_steps 示例（dist 布局：bin/config/libs/data）：

```bash
mvn -q package -DskipTests ${VERIFY_MODULES:+-pl $VERIFY_MODULES -am}
mkdir -p build/verify-out/libs
cp <本次编出的模块 jar> build/verify-out/libs/   # 增量覆盖持久树，不动其余 jar
# output_dir 配 build/verify-out
```

前端示例：构建直接产出 `dist/` 即部署形态，`output_dir=dist`，无需组装。

5. 对产物树生成同步清单（相对路径 + sha256 + size），连同产物打 zip 经
   ⚠️ `POST /api/agent/verify/runs/{id}/bundle`（节点 token 认证，同 CAP-34 产出上传
   先例；**产物字节一律走 HTTP 不入帧**）上传服务端暂存。**包大小上限**平台配置
   `devmind.verify.bundle-max-mb`（默认 512），超限构建侧直接失败
   `VERIFY_BUNDLE_TOO_LARGE`，run 记 FAILED（BUILDING）并引导精简 output_dir。

#### 步骤 3 补丁下发（PATCHING）

服务端暂存包路径 `data/verify-bundles/<runId>/`（成功即删，失败保留 7 天定时清理）。
bundle 上传/下载端点均校验 **run ↔ 节点绑定**：上传只接受该 run 指定构建节点的
token，下载只接受指定环境节点的 token（节点 token 是长寿命凭证，不绑则任意注册
节点可拉任意产物包）。

**补丁同步是 runner 侧新逻辑**（环境节点 runner 内实现，非服务端 exec 逐文件下发——
exec 帧无字节通道）。环境节点 runner 按序执行（⚠️ 经协议 v22 专用 `verify_patch` 帧
下发，`verify_log` 流式回传进度）：

1. `patch_pre_hook`（可空跳过；**失败即终止，不覆盖任何文件**，现场保持旧版本。
   一旦 pre_hook **执行成功即登记**——此后任何失败都必须尝试 post_hook 恢复，
   见失败恢复；⚠️ pre/post_hook 与 build_steps 一样过 runner `execAllowlist` 白名单）；
2. 反向拉取 ⚠️ `GET /api/agent/verify/runs/{id}/bundle`（节点 token）下载到
   `patch_dir/.devmind-verify-staging/`，校验清单哈希（前端静态场景 staging 目录
   短暂暴露在 web root 下、逐文件 mv 期间 nginx 会服务新旧混合资源——验证环境
   可接受）；
3. 与**本需求**的节点侧 manifest 比对，**暂存区全量就绪后**逐个 mv 覆盖差异文件
   （新增+变更），并删除「旧 manifest 有、新 manifest 无」的文件；
4. 写入新 manifest 替换旧文件（先写 tmp 再 mv，防半截清单）；
5. `patch_post_hook`（可空跳过）。

**manifest 存放与分键**：存 **runner 侧状态目录** ⚠️ `<workspaceRoot>/_verify/manifests/<manifestKey>.json`
（manifestKey = 服务端生成的环境×需求键）——runner 是唯一读写者，不依赖部署目录父级可写、
不会被当静态资源暴露；服务端不存。**按需求分键**：同环境被多个需求绑定时，各自
manifest 独立，删除语义只及本需求下发过的文件（覆盖冲突仍可能存在，见 §8 已知限制，
但不再互删）。

**同步语义红线**：平台只增/改/删 **本需求 manifest 覆盖范围内**的文件；`patch_dir`
中从未出现在本需求 manifest 的文件（环境本地配置、logs/、data/、其他需求下发的
文件等）**永不触碰**。

**失败恢复闭环**：pre_hook 成功后的任何失败——拉包失败、哈希校验失败、覆盖中途失败
（磁盘满/权限/节点断连）——**仍尝试执行 `patch_post_hook`** 拉起服务，run 记 FAILED
（PATCHING）；覆盖中途失败另在 `failure_summary` 标注「现场可能为混合版本」。
自动回滚不做（非目标），恢复手段 = 修复后再次触发验证或下次正式全量部署。

manifest 缺失（首次验证/节点重建）→ 退化为产物树**文件级全量同步**一次
（同样不碰 manifest 外文件），之后恢复增量。

#### 步骤 4 冒烟（TESTING）

经 `TestRunService` 以 `environmentId` 起该项目 `kind=smoke` 的冒烟套件（多个取
最近更新者），baseUrl 走 CAP-10 既有解析链（环境变量 `baseUrl`/`BASE_URL` → 首个
节点）。项目无冒烟套件 → 跳过并在 run 上标注「未执行冒烟」。

#### 状态机与追溯

`status`：⚠️ RUNNING → SUCCESS/FAILED（**无 QUEUED**——受理即虚拟线程上链，
需求互斥由应用层分段锁挡在受理前，不需要排队态；僵尸 run 由巡检收口判 FAILED）；
`stage` 记录当前/失败阶段（PUSHING/BUILDING/PATCHING/TESTING）。触发即返回
runId，全链异步。`commit_sha`（远端 HEAD）、`push_status`（PUSHED/SKIPPED）、
变更文件数（`files_changed`，全量同步 = 全量文件数）、`stage_timings_json`
（各阶段耗时 map）落 verify_runs 追溯。

### FR-04 Agent 自主触发通道

会话上下文装配（CAP-33/34 ContextPackage）为挂需求且已绑定验证环境的会话物化
助手脚本 ⚠️ `.devmind/input/verify/devmind-verify`（POSIX sh；Windows 节点同目录物化
`devmind-verify.bat` 配套）。⚠️ **物化通道 = ContextPackage 既有 `inputs` 机制**
（flow 侧 `VerifyContextProvider`，节序 60）——不动包 schema，老 runner 无感；
`.devmind` 目录本就在平台物化路径 `info/exclude` 排除集内，不占 git 跟踪文件。
三件套门控：需求已绑验证环境 + 项目 `verify_in_dev_enabled` 开 +
`devmind.verify.server-url` 已配（缺任一项不物化；server-url 缺失是常态部署默认，
脚本无法猜反向代理后的回连地址）。⚠️ **瘦上下文例外**：lean 执行会话正是验证链
受用者，`SessionContextService` 对 lean 不再置空 requirementId，CAP-40 附件投送
的豁免改由 provider 读 `lean` 标志自行判定；token 重建路径（TTL 过期 runner 重拉）
重跑装配即重签续期。

脚本内嵌 **verify 作用域 token**——⚠️ `VerifyTokenService`（common）HMAC 签发
`dmv_<base64url(sid.rid.exp)>.<hex hmac>`，绑 sessionId + requirementId + 过期
（`devmind.verify.token-ttl-hours` 默认 24h），密钥落 `data/verify.key`（仿 auth.key）；
仅授权 `POST /api/verify/trigger`（permitAll + 服务内验签），会话结束即失效由触发端
存活检查兜底。

⚠️ 平台对外基址由 **`devmind.verify.server-url`**（新增配置，空 = 不物化助手；人工
触发入口不受影响）显式给出——反向代理/自签 https 部署（如 8.135.37.194:38880）
服务端无法自知公网地址。

agent 经 Bash 调用 `devmind-verify [--note "..."] [--modules "user-service,common"]`：

- `--note`：验证备注，落 verify_runs.note；
- `--modules`：**编译范围 hint**，AI 按本次改动判断；平台不解释语义，原样经
  `VERIFY_MODULES` 环境变量透传进 build_steps。判断错了代价只是「验证了旧类、
  冒烟抓住、再跑一轮」，有配额兜底；不传 = 全量构建，永远正确；
- 成功受理：输出 runId 与「结果将回灌本会话」提示，立即返回（不阻塞 agent）；
- 被拒：输出结构化错误码与引导文案（`VERIFY_ENV_UNBOUND` / `VERIFY_ENV_NOT_PATCHABLE`
  / `VERIFY_BUILD_NO_OUTPUT` / `VERIFY_NO_NEW_COMMIT` / `VERIFY_NO_BRANCH` /
  `VERIFY_NODE_OFFLINE` / `VERIFY_REPO_UNREACHABLE` /
  `VERIFY_RUNNER_UPGRADE_REQUIRED` / 并发冲突 409 / 超限 429 / 开关关闭 403）。

skill 注入块（CLAUDE.local.md）同步广告该命令的存在与使用时机
（「完成一个可验证的改动并 commit 后，运行 devmind-verify 验证；只改了部分模块时
可带 --modules 加速构建；受理后不轮询，结果自动回灌」）。

⚠️ **协议 v22 帧**（runner 必须认识，否则触发报 `VERIFY_RUNNER_UPGRADE_REQUIRED`
引导升级）：

| 方向 | 帧 | 说明 |
|---|---|---|
| 下行 | `verify_push` / `verify_pack` / `verify_patch` | 三阶段指令（产物字节不入帧，走 HTTP） |
| 上行 | `verify_log` / `verify_ack` | 流式日志 / 终态应答（ok、error、fileCount、filesChanged） |

### FR-05 限额与互斥

- **需求级互斥**：同需求同时刻仅一个 verify run（QUEUED/RUNNING 再触发 → 409）。
  实现 = 应用层按 requirementId 分段锁 + 事务内复查进行中 run（不靠数据库部分
  唯一索引，H2/PG/MySQL 方言不一致）。阶段超时/节点断连判 FAILED 时锁随之释放。
- **项目级开关**：`projects.verify_in_dev_enabled`（默认关，Boolean 可空 + getter
  兜底，**不加 @ColumnDefault**——CAP-72 同款红线）；关闭时人工/agent 触发一律 403。
- **日配额**：`projects.verify_daily_quota`（整数，默认 20，0 = 不限）。计数口径 =
  该项目当日（服务器本地时区）**已受理**的 verify runs 数（含失败——受理即计，
  防 agent 刷失败重试绕过）；超限 429，文案告知次日重置与人工调整入口。

### FR-06 结果回灌与通知

verify run 终态时：

- **回灌会话**：经 common 会话内核 `AbstractSessionRuntime.injectInput` 向进行中
  会话注入一条 system 来源输入（agent 下一轮自然看到，自主决定修复迭代）。
  注入目标：agent 触发 → **触发会话**；人工触发 → 该需求**全部进行中会话**。
  内容 = 结果 + commit_sha 短号 + 变更文件数 + push_status（SKIPPED 时显式标注
  「验的是远端 HEAD，本地未推送改动不在其中」）+ 失败阶段与日志尾部（截断脱敏）+
  成功时的访问地址。
- **访问地址来源**：该环境 `variables` 中的 `baseUrl`/`BASE_URL`（与 CAP-10 冒烟
  基址同源，不加新列）；空则不展示。
- **无进行中会话**：落验证历史 + CAP-06 通知需求负责人（成功带访问地址，失败带
  失败摘要与日志链接）。

### FR-07 人工入口与收口可见性

- 需求详情页新增「验证」Tab：verify run 历史列表（状态/触发人 AGENT·MANUAL/
  commit/变更文件数/耗时/失败阶段）+ 「立即验证」按钮（与 agent 触发同链同限额
  同互斥，支持 `force` 跳过无新提交校验——用于失败修复环境后重试同 commit）+
  run 详情（各阶段耗时、`failure_summary` 全文、日志 Hub 入口）。
- 收口确认弹窗展示**最近一次 verify run 状态**（成功绿标/失败红标/从未验证灰标），
  仅提示不门禁——「验证通过才允许收口」的硬门禁另立 CAP。

## 3. 插件化接口

无新增 SPI。编排落 **devmind-flow**（CAP-17 PipelineOrchestrator 先例：flow 已直接
依赖 devmind-build/deploy/test 实现）；会话注入走 common 会话内核
`AbstractSessionRuntime.injectInput`；agent 触发物化复用 CAP-34 ContextPackage 管线
（⚠️ 既有 `inputs` 通道，不改包 schema）；帧通道 ⚠️ 走协议 v22 专用 verify 帧
（`AgentNodeConnector` SPI 三个默认方法挂在 common，flow 不依赖 agent；bundle 字节
走节点 token 认证的 HTTP 端点——产出上传/拉包先例）。

**runner 侧新逻辑显式立项**（瘦 jar 无 Spring，均为新增，⚠️ 落 `VerifyHandler` +
`RunnerWorkspace.verifyPush/prepareVerify`）：

- **构建节点**：verify 工作区物化与 `reset --hard <commitSha>` 维护、build_steps 执行
  （含 `VERIFY_MODULES` 注入、⚠️ execAllowlist 白名单同一防线）、产物树 manifest
  生成（sha256）、zip 上传；
- **环境节点**：bundle 下载与哈希校验、staging 区管理、manifest 比对、逐文件 mv
  覆盖与删除、manifest tmp+mv 原子写、pre/post 钩子执行（⚠️ 过 execAllowlist）
  与失败恢复闭环；
- **版本协商**：⚠️ 服务端 `AgentConnectionRegistry.supports(nodeId, VERIFY_FRAMES)`
  探测帧能力；不认识的 runner 被选中时触发报 `VERIFY_RUNNER_UPGRADE_REQUIRED`
  （引导升级，不静默异常）。dist 打包与 runner 托管自升级（退出码 42）配套发布。

## 4. 依赖关系

依赖 CAP-13（Requirement 模型与需求级归属）、CAP-51（需求分支 `feature/req-<id>`
与需求级互斥口径、runner 工作区物化）、CAP-08（build_steps 与 build_configs 构建配置，
本期为其加 `output_dir` 列）、CAP-10（冒烟套件与 TestRunService 环境解析链）、
CAP-12（执行底座与日志 Hub）、CAP-34/36（ContextPackage 物化 + exec 帧通道 +
节点 token 认证）、CAP-06（通知）、CAP-42（push/ls-remote 凭证）、CAP-72（项目级
开关先例）。**不依赖 CAP-09 部署执行器**——补丁更新不走部署计划，是 runner 反向
拉取 + exec 帧钩子。

## 5. 数据模型

```
requirements 加列：verify_environment_id BIGINT NULL  -- environments.id

environments 加列：
  patch_dir        VARCHAR(512) NULL   -- 部署目录（补丁覆盖目标；缺 = 不可验证）
  patch_pre_hook   VARCHAR(1024) NULL  -- 覆盖文件前命令（停服/备份；空 = 直接覆盖）
  patch_post_hook  VARCHAR(1024) NULL  -- 覆盖文件后命令（启服/热加载；空 = 不动进程）

build_configs 加列：
  output_dir       VARCHAR(256) NULL   -- 部署物树目录（相对工作区根，与 patch_dir 同构；缺 = 不可验证）

projects 加列：
  verify_in_dev_enabled BIT NULL  -- 默认关（getter 兜底，不加 @ColumnDefault）
  verify_daily_quota    INT NULL  -- 默认 20（getter 兜底），0 = 不限

verify_runs(
  id                BIGINT PK,
  requirement_id    VARCHAR(32) NOT NULL,   -- ⚠️ requirements.id 是字符串主键
  project_id        VARCHAR(32) NOT NULL,
  trigger_type      VARCHAR(8) NOT NULL,     -- AGENT / MANUAL
  triggered_by      VARCHAR(64),             -- 人工触发用户名；agent 触发为会话 id
  environment_id    BIGINT NOT NULL,
  commit_sha        VARCHAR(64),             -- 远端需求分支 HEAD
  push_status       VARCHAR(8),              -- PUSHED / SKIPPED（无会话工作区纯远端）
  stage             VARCHAR(16),             -- PUSHING/BUILDING/PATCHING/TESTING（当前/失败阶段）
  status            VARCHAR(16),             -- ⚠️ RUNNING/SUCCESS/FAILED（无 QUEUED，受理即上链）
  modules_hint      VARCHAR(512),            -- devmind-verify --modules 透传值（追溯用）
  files_changed     INT,                     -- 补丁下发文件数（全量同步 = 全量文件数）
  duration_ms       BIGINT,                  -- 全链总耗时
  stage_timings_json LONGVARCHAR,            -- 各阶段耗时 map（@Lob + LONGVARCHAR 红线照旧）
  failure_summary   LONGVARCHAR,             -- 失败阶段+日志尾部（脱敏截断；混合现场标注也在此）
  note              VARCHAR(512),            -- devmind-verify --note / 人工备注
  created_at, finished_at
)
```

同步清单 manifest（文件路径+哈希）存**环境节点 runner 侧状态目录**
（⚠️ `<workspaceRoot>/_verify/manifests/<manifestKey>.json`，按需求分键），服务端不存——节点清单
丢失只导致该需求一次全量同步，无正确性风险。产物暂存包
`data/verify-bundles/<runId>/` 成功即删、失败留 7 天。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | ⚠️ `/api/projects/{pid}/requirements/{rid}/verify?force=` | 人工触发（项目作用域端点；限额/互斥/开关同 agent 通道；force 跳过无新提交校验） |
| GET | ⚠️ `/api/projects/{pid}/requirements/{rid}/verify-runs` | 验证历史（分页） |
| GET | `/api/verify/runs/{id}` | run 详情（阶段耗时/failure_summary 全文/日志 Hub 入口） |
| POST | `/api/verify/trigger?token=` | agent 通道（verify 作用域 token 自证，permitAll+服务内验签；body 带 note/modules） |
| POST | ⚠️ `/api/agent/verify/runs/{id}/bundle?token=` | 构建节点上传产物包（节点 token，校验 run↔构建节点绑定，大小上限） |
| GET | ⚠️ `/api/agent/verify/runs/{id}/bundle?token=` | 环境节点拉取产物包（节点 token，校验 run↔环境节点绑定） |

需求读写 DTO 增 `verifyEnvironmentId`；环境读写 DTO 增 `patchDir`/`patchPreHook`/
`patchPostHook`；构建配置读写 DTO 增 `outputDir`。

## 7. 验收标准

1. 需求绑定已配 `patch_dir` 的环境且构建配置已配 `output_dir`（产物经 build_steps
   组装为与 patch_dir 同构的完整树）后，会话内 agent commit 改动并执行
   `devmind-verify`：verify run 走完 推送→增量构建→pre 钩子→补丁下发（仅变更
   文件）→post 钩子→冒烟，触发会话收到结果事件（含变更文件数与访问地址）。
2. pre 钩子失败：链终止且**文件未被覆盖**（现场保持旧版本），会话收到钩子错误输出。
3. pre_hook 成功后拉包失败/哈希校验失败：run 记 FAILED（PATCHING）且 **post_hook
   仍被执行**（服务被拉起），现场保持旧版本。
4. 第二次验证只下发**增量变更文件**（manifest 比对生效）；删掉节点侧本需求
   manifest 后第三次退化为全量同步一次，第四次恢复增量。
5. 同步语义：`patch_dir` 中手工放置的本地配置文件（从未进过 manifest）在全量与
   增量同步后均原样保留；构建产物树中删除的文件在下一次同步后从 `patch_dir` 消失。
6. 同环境绑两个需求交替验证：各自 manifest 独立，**互删不发生**（同名部署文件
   覆盖冲突属 §8 已知限制）。
7. 覆盖中途失败（如下发一半 kill runner）：run 记 FAILED（PATCHING），post_hook
   仍被尝试执行，`failure_summary` 标注混合现场；修复后再次触发可恢复。
8. 构建在独立 verify 工作区进行：构建期间会话工作树的未提交改动不受影响，且
   **未 commit 的改动不进入构建产物**（checkout --detach 远端 HEAD）。
9. `--modules` 透传：build_steps 环境内可见 `VERIFY_MODULES`；部分构建只替换
   产物树中本次编出的文件，未重编模块的部署文件**不被删除**（持久产物树增量组装
   生效）。
10. 构建基准是需求分支**已 push 的远端 HEAD commit**；远端 HEAD 与上次 SUCCESS
    相同 = 409 `VERIFY_NO_NEW_COMMIT`；上次 FAILED 时同 SHA 可重试；人工
    `force=true` 可跳过校验。
11. 会话节点离线且该需求有历史会话工作区 → 409 `VERIFY_NODE_OFFLINE`；从无会话
    工作区 → push 跳过、run 记 `push_status=SKIPPED` 且回灌内容标注。
12. 同需求并发触发 → 409；超日配额 → 429（失败 run 也计数）；未绑定环境 →
    `VERIFY_ENV_UNBOUND`；环境缺 `patch_dir` → `VERIFY_ENV_NOT_PATCHABLE`；
    构建缺 `output_dir` → `VERIFY_BUILD_NO_OUTPUT`；项目开关关闭 → 403；
    绑定他项目环境 → 400；低版本 runner 被选中 → `VERIFY_RUNNER_UPGRADE_REQUIRED`；
    产物包超限 → `VERIFY_BUNDLE_TOO_LARGE`；bundle 端点拒绝非指定节点 token。
13. 任一阶段失败：链终止，会话收到失败阶段 + 日志尾部，历史与各阶段耗时可查
    （Tab 详情含 failure_summary 全文与日志入口）。
14. 无进行中会话时人工触发：结果走通知；人工触发时该需求有多个进行中会话 →
    全部收到回灌。
15. 收口确认弹窗显示最近验证状态；PROD 环境不出现在绑定选项中。
16. Windows 会话节点物化 `devmind-verify.bat` 可正常触发；Windows 环境节点补丁
    下发（含停服钩子场景）正常。
17. 存量需求/环境/构建配置（新列 null）行为零变化。

## 8. MVP 范围与非目标

**MVP**：FR-01~07 全量（单环境绑定、串行四步链、限额互斥、回灌与通知、验证 Tab、
runner 同步逻辑与版本协商）。

**MVP 限制**（触发即报明确错误码，不静默降级）：

- **多库聚合项目（CAP-31）不支持**：需求分支跨多库时逐库 checkout/构建/产物边界
  复杂度另立 CAP，本期触发报 `VERIFY_MULTI_REPO_UNSUPPORTED`。

**非目标（另立 CAP）**：

- 全量构建/部署/测试链——那是 CAP-17/08/09 的既有形态，本能力不做也不替代；
- verify 构建进 CAP-08 构建历史/构建列表——它是验证链内部步骤，追溯在 verify_runs；
- 「产物 → 部署布局」映射机制——同构约定 + 项目构建脚本组装已覆盖，不建平台
  映射配置；
- 验证通过作为收口硬门禁（本期仅可见性提示）；
- 补丁导致的环境漂移治理：验证环境被补丁改脏是**预期行为**，环境恢复靠下次正式
  全量部署（CAP-09）重置，本期不提供「还原到基线」动作；
- 覆盖中途失败的自动回滚（仅 post_hook 尽力恢复，混合现场靠再次验证或全量部署
  收口）；
- 同环境多需求的同名部署文件覆盖冲突（manifest 分键后互删已消除，覆盖冲突是
  部署物本身打架，规避方式 = 绑不同环境）；
- 构建系统级增量优化（mvn 模块裁剪的准确性与 `-am` 依赖闭包等，属项目构建脚本
  职责；`--modules` 只是 AI hint，平台不保证其正确性）；
- agent 修复迭代的轮次自治策略（靠日配额兜底，精细预算另议）；
- PROD 环境的任何自动动作（红线）。

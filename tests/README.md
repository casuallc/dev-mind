# tests/ — 可复用 E2E 验证脚本

对真实启动的服务跑端到端验证的脚本集，随 git 提交。与 `mvn test` 单测互补：单测管逻辑回归，这里管「不起服务跑一遍不算完成」的那遍。

## 通用约定

- **运行产物一律落 `tmp/`**（gitignored）：日志、runner 工作区、临时 json/zip。本目录只放脚本与 fixtures，别把生成物写进来。
- **前置看脚本头注释**：多数要 app 已起（:8080，个别 :8081/:18090）；涉及 runner 的先 `mvn -q install -DskipTests` 出 `devmind-agent-runner.jar`。
- Python 脚本只用标准库（Windows 控制台已处理 UTF-8）；`.mjs` 需 Node 18+（原生 WebSocket/fetch）；`.sh` 用 Git Bash；从仓库根目录跑。
- 新脚本命名 `capXX_*.py` / `e2e-<主题>.sh`，头部注明前置与覆盖点，禁硬编码密钥/本机密码。

## fixtures/（测试双端）

| 文件 | 用途 | 启动方式 |
|------|------|----------|
| `TestSshServer.java` | 内存 SSH 服务器（sshd），cap07~10 的上传/远程执行目标 | 先编译（见下），verify 脚本会自动拉起；手工：`java -cp <cp> TestSshServer 2222 test testpw` |
| `agent-mock.js` | 假 Agent HTTP 端（cap07 健康检查） | `node agent-mock.js 9100 tok123` |
| `api-mock.js` | 假 OpenAPI 端（cap10 apiDocSource） | `node api-mock.js 9300` |
| `embedding-mock.py` | 假 OpenAI 兼容 embedding 端（`POST /v1/embeddings`，每行常量单位向量 → 余弦恒 1.0，与 LIKE 降级的 score=0 可区分），控制面 `/__dims` 改维度、`/__status` 注入故障（body 回显 Authorization 验凭据脱敏）、`/__state` 回看请求 | `python embedding-mock.py [port]`（默认 18193，cap48 脚本自动拉起） |
| `jira-mock.py` | 假 Jira Server（`/rest/api/2`，创建 issue/任务类型/创建字段元数据（新旧两种端点）/优先级/可指派用户/单条读取/搜索），控制面 `/__state` 暴露收到的 payload、`/__createmeta` 注入字段目录并可按目录校验必填 | `python jira-mock.py [port]`（默认 18192，cap47 脚本自动拉起） |
| `chat-mock.py` | 假 OpenAI 兼容对话端（`POST /v1/chat/completions` → `choices[0].message.content` 为当前回复文本），控制面 `/__reply` 改回复、`/__status` 注入故障（body 回显 Authorization 验凭据脱敏）、`/__state` 回看请求（model/prompt/role/auth/path/hasMaxTokens）。**刻意不实现 `/v1/embeddings`**：探针没按 kind 分派时会拿到 404 当场报错 | `python chat-mock.py [port]`（默认 18194，cap48 脚本自动拉起） |

TestSshServer 编译（Git Bash，一次性，产物 `.class` 已 gitignore）：

```bash
M2=~/.m2/repository
javac -cp "$M2/org/apache/sshd/sshd-core/2.16.0/sshd-core-2.16.0.jar;$M2/org/apache/sshd/sshd-common/2.16.0/sshd-common-2.16.0.jar;$M2/org/apache/sshd/sshd-sftp/2.16.0/sshd-sftp-2.16.0.jar;$M2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar" \
  -d tests/fixtures tests/fixtures/TestSshServer.java
```

## 脚本清单

### 能力验证（Python，app :8080 除非另注）

| 脚本 | 覆盖 |
|------|------|
| cap02_verify.py | 项目 CRUD/查重 409/标签筛选 |
| cap03_verify.py | 文档库：建档/模板/多版本/diff/回退/git 同步 |
| cap04_verify.py | 知识库 FR-01~08 + 真实会话注入 |
| cap44_verify.py | CAP-44 知识库容器化+向量检索：库 CRUD/级联删、legacy 兜底经验库、索引状态机、向量检索排序（需 app 配 `devmind.knowledge.embedding.provider=mock`，默认 :18090 独立实例） |
| cap45_verify.py | CAP-45 飞书文档对接：FEISHU 集成+连接测试、docx/wiki/doc 三形态导入（externalId 判重+contentHash 变更检测）、URL 归一化、重同步 updated/unchanged/失败保留旧内容（需 node 起 fixtures/feishu-mock.js 由脚本自起、app 配 mock embedding，默认 :18090 独立实例） |
| cap46_verify.py | CAP-46 知识库 AI 会话：绑库问答（ChatView 回传 knowledgeBaseId、库不存在 400）、事件流断言库概览节 <knowledge-base> 与两轮 <knowledge-context> 注入（来源标注）、不绑库问答无注入（脚本自起 fake runner 节点连 :18090，需 runner jar 已构建 + app 配 mock embedding 独立实例） |
| cap48_verify.py | CAP-48 模型接入管理：迁移种子端点、端点 CRUD/校验、连接测试（实测维度回写/维度变化告警/失败诊断且凭据脱敏）、解析链（库级覆盖→平台默认→无，停用/删除后回落不回写）、索引血缘与维度失配诊断（DIMENSION_MISMATCH / NO_EMBEDDING 均不静默空结果）、重建索引（全库/只失配）、删除引用保护、通用模型（CHAT）端点段：按 kind 分派打 /chat/completions（含草稿预检与 RERANK 400）、不吃向量语义（topK/threshold 落 null 且越界不报错）、mock 对话零网络、测试消息回显模型真实回复、平台默认按类型各自唯一、知识库不能绑对话端点、对话默认不劫持向量解析链（脚本自起 fixtures/embedding-mock.py + fixtures/chat-mock.py，默认 :18090 独立实例；**会删掉迁移种子端点，放在 cap44/45/46 之后跑**） |
| cap47_verify.py | CAP-47 自建需求推送 Jira：push-targets/options/可指派用户（GDPR 退 username）、建 issue payload（含回链、空参数不写）、转托管不动托管字段、重复推送 409、无凭证 400 引导绑定、手动 refresh、同步 run 不重复建需求、FR-08 动态必填字段（create-fields 分区与控件映射/候选值与预填、缺字段被 Jira 逐字段拒、动态字段 payload 形态、护栏拦越权与超深取值、旧端点兜底、读端点抖动降级、FR-10 个人推送模板（四元组 upsert/多用户隔离/未登录 401/targets 带回模板/项目无关选项端点））（脚本自起 fixtures/jira-mock.py，默认 :18090 独立实例） |
| cap06_verify.py / cap06_integration.py | 通知中心 REST / 集成链路 |
| cap07_verify.py | 服务器适配：SSH/HTTP 连通、模板白名单、上传下载、凭证加密（自动拉起 fixtures） |
| cap08_verify.py | 构建执行器：多步骤/上下文 env/并发 409/远程构建/WS 日志流 |
| cap09_verify.py | 部署执行器：幂等 409/备份/失败自动回滚/确认门 |
| cap10_verify.py | 测试执行器：OpenAPI 套件/API 执行/报告/失败转缺陷（自动拉起 fixtures） |
| cap11_verify.py | 发版执行器：tag/版本递增/回滚删 tag |
| cap21-project-default-node.py | 项目默认执行节点继承与覆盖 |
| cap23_verify.py | 项目仓库 git 克隆：状态机/重试/WS 帧（配 cap23-ws-test.mjs） |
| cap28_e2e.py / cap28_e2e2.py | 工时：仓库订阅/条目 CRUD/git 导入/日报周报（:8081） |
| cap29_e2e.py | 全局仓库登记 + 项目关联 + 工时订阅扫描（:8081） |
| cap32_e2e.py | 附件：上传/鉴权/Content-Disposition/chat 引用 |
| cap33_verify.py | 场景化会话与上下文装配（自建 fake runner） |
| cap34_fr04_08_verify.py / cap34_reattach_verify.py | runner 调度：断连对账/服务端重启 reattach/exit 路由 |
| cap37_e2e.py ~ cap40_e2e.py | 产出回传、流程串联、需求附件上下文投送（协议 v3/v4） |
| e2e-agent-node.py | CAP-21 节点全链路：注册→会话→授权→优雅退出→离线 409 |
| e2e-cap41.py / b / c | CAP-41 工作日志空间：懒创建/守卫/种子模板/日报周报生成 |
| e2e-cap41-m3-push.py | CAP-41 M3：worklog 远端绑定 + push（协议 v6，file:// bare 库） |
| e2e-cap42.py | CAP-42 每用户固定工作区：固定布局/finish 不 push 不删/占用冲突 409/手动收口闭环/脏与合并冲突两负例重试（协议 v7，file:// bare 库） |
| e2e-git-import-range.py / e2e-worklog-keyword.py | git 导入范围扫描 / 工时条目关键字筛选（:8081） |
| skill-import-e2e.py | skill zip 导入：root/包裹结构/409/overwrite（:8081） |

### Shell（Git Bash）

| 脚本 | 覆盖 |
|------|------|
| e2e-cap25.sh | 服务端+runner 全链路：CLONE 项目→远程会话→worktree→push 回远端 |
| e2e-cap32-attachments.sh | 附件 description 与关键字搜索（multipart 中文坑见头注释） |
| e2e-cap34.sh | dist 包起服务端:18090+runner：无节点 409/"local" 400/问答全链路 |
| e2e-cap34-{list,cleanup,del-node}-mysql.sh | CAP-34 E2E 误写共享 MySQL 的查看/清理（**操作 156 共享库，慎用**） |
| e2e-chat-stream.sh | fake 会话结构化事件解析（:8081，executor=fake） |
| e2e-resume-chat.sh | 问答「继续对话」claude --resume 续接（真实 runner） |
| e2e-install-script.sh | 一键安装脚本生成与执行（sh 实跑 + ps1 语法解析） |
| e2e-integration-test.sh | 集成连通性测试端点（未保存试连） |
| e2e-req-open-filter.sh | 需求列表 status=OPEN 伪状态筛选 |
| e2e-runner-upgrade{,-main,-force}.sh | CAP-21 FR-09 runner 自升级：BUSY 推迟/换包重启/强制升级 |
| e2e-stacktrace.sh | 错误响应堆栈透传（local profile） |
| verify-skill-import.sh | /api/skills/import 报错文案 + BOM 兼容 |

### 前端布局回归（Node + 无头 Chrome，需 app :8080 与前端 dev :5173 同时起）

| 脚本 | 覆盖 |
|------|------|
| e2e-requirements-layout.mjs | 需求列表：整页不出纵向滚动条、表体内部滚动（`scroll.y` 为实测值）、表头吸顶、分页条常驻；自带数据（临时项目「布局校验-临时」造 30 条需求，跑完连项目一起删，`--keep` 保留）。浏览器路径用 `CHROME_PATH` 覆盖，截图落 `tmp/layout-check-requirements.png` |
| e2e-layout-pages.mjs | 全站布局巡检：34 个路由（项目页/个人页/后台页/项目设置，含知识库详情页取库里第一个库、没有则跳过该路由）逐个打开，并依次点开页内第一组 `Segmented` 的每个视图，逐个断言 `.ant-layout-content` 与 document 都不溢出、且没有「越界又无滚动祖先」的元素（漏了滚动容器 → 内容被裁或顶破卡片）。只读，不改数据（当前项目取库中第一个）。`--only knowledge,worklog` 只跑匹配路由（**别写前导斜杠**，Git Bash 会做路径转换；片段是 `includes` 匹配，`admin/knowledge` 会同时命中知识库列表页与详情页）、`KB_ID=<库ID>` 指定知识库详情页巡检哪个库（默认取库里第一个）、`--window 1366,768` 换视口（矮视口更易暴露问题，建议两轮都跑）、`--shots` 每页存图、`--dump` 失败时打印内容区组件树（含高度/滚动量）定位元凶。截图与 report.json 落 `tmp/layout-sweep/` |
| e2e-model-form-probe.mjs | 「模型接入」抽屉内「测试连接」：**填进表单的凭据与超时必须真的进探针请求**（浏览器里那条「表单取值 → 草稿请求」的路径，cap48_verify.py 走接口测不到）。自起一个强制鉴权的假端点（Bearer 不匹配一律 401，与 vLLM 行为一致），无头 Chrome 开抽屉、选类型、填表、点「测试连接」，逐项断言：探针打对路径、**带上 apiKey**、提示条成功、表单填的超时生效（拖 5 秒的假端点按 2 秒超时失败）。只读，不点「保存」。前置同上一行（app + 前端 dev），另需前端 origin 在后端 CORS 白名单内（默认只放 5173/8080，Vite 因端口占用换到 5174 时脚本会直接报错并给处置办法）。报告与失败截图落 `tmp/e2e-model-form-probe/` |

### WS 帧探针（Node，被 verify 脚本调用或手工）

`ws_check.mjs`（通知流）、`cap08-ws-test.mjs <buildId>`、`cap09-ws-test.mjs <depId>`、`cap10-ws-test.mjs <runId>`、`cap23-ws-test.mjs <repoId>`

### PowerShell

`service-e2e.ps1 <home>` — runner Windows 服务 install→start→restart→stop→uninstall，**需管理员**。

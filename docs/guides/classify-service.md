# 分类服务（classify）运维指南 — CAP-57

laya 决策边车的平台化管控：**服务实例起停**（proc 帧）、**安装包分发**（pkg 帧拉取模式）、
**在线试分类**（playground）。与业务流程完全解耦——知识分诊已不再调决策引擎，分类服务
是面向未来工单分类/邮件分类的独立底座。

后台入口：`/admin/classify/instances`（实例）、`/admin/classify/packages`（安装包）、
`/admin/classify/playground`（试分类），AdminLayout「项目与资源」分组。

## 1. 概念与链路

```
安装包（zip：边车程序 / 模型权重 / 语料数据）
  │  页面上传（multipart ≤4GB，流式落盘 + sha256）
  ▼
服务端存储 data/classify-packages/pkg-<id>.zip
  │  「分发」→ pkg 帧下发节点（协议 v15 门控）
  ▼
runner 节点：GET /api/agent/classify/packages/{id}?token= 拉包
  → 校验 sha256 → 解 zip → 原子 rename 到 <workspaceRoot>/classify/packages/pkg-<id>/
  → pkg_ack 回收 installDir（绝对路径，落 installs 表）
  │
实例（节点 + 端口 + 程序包 + env + 启动命令）
  │  「启动」→ proc 帧（argv 数组，不过 shell）
  ▼
runner：ProcessBuilder 起进程，cwd=程序包 installDir，pidfile 落 classify/run/inst-<id>/
  │  健康轮询（服务端 @Scheduled，默认 30s）打 baseUrl/healthz
  ▼
STARTING →（宽限期内 healthz 通）→ RUNNING；宽限期（默认 10min）外仍不通 → UNHEALTHY
```

- **崩溃不自动拉起**（CAP-57 非目标）：进程退出 → 状态对账翻 STOPPED 并记「进程已退出」；
  healthz 不通但进程在 → UNHEALTHY。人工在实例 Drawer 里重启。
- **runner 重启不杀托管进程**（刻意与 session/exec 分岔）：runner 启动时按 pidfile + proc.json
  对账，进程状态续得上。
- 实例 env 支持 **`${PKG_DIR:<packageId>}`** 占位符 → 展开为对应包**在该节点**的 installDir
  （未分发到该节点则 start 报 409，话术指到安装包页）。

## 2. 节点升级 v15（前置）

proc/pkg 帧是 WS 协议 v15 新增，**老 runner 不认识会静默丢帧**——服务端 supports() 门控，
协议 <15 的节点操作直接 409「请升级 runner」。升级路径：

1. 服务端构建出 v15 jar：`mvn -q install -DskipTests`（产物 `devmind-agent-runner/target/devmind-agent-runner.jar`）。
2. 页面「Agent 节点」→ 上传 runner 包 → 节点 Drawer「升级」（WinSW 托管的本机 runner 走退出码 42 自换 jar）。
3. **注意**：长驻 runner 不会自动换新 jar；140.88 GPU 节点升完重启后 `protocolVersion` 应显示 15。

## 3. 打包 laya-sidecar（SIDECAR_APP 包）

以 `tools/laya-sidecar` 为源，打一个**自含 venv** 的 zip（节点上无 torch 依赖也能跑的就是这个包）：

```bash
cd tools/laya-sidecar
# 建议在有 GPU/torch 的构建机（如 140.88 本机或 build-224）上做：
python -m venv build/venv && build/venv/bin/pip install -r requirements.txt
zip -r laya-sidecar-0.3.6.zip app/ venv/ models.json
```

zip 根布局要求（实例默认 argv 的约定）：

```
<zip 根>
├── app/            # FastAPI 应用（app:app）
├── venv/           # 自带解释器（pythonBin 默认 venv/bin/python；Windows 节点填 venv/Scripts/python.exe）
└── models.json     # 槽位声明（可选）
```

上传：「分类安装包」→ 类型「边车程序包」→ Dragger 选 zip。GB 级权重包（MODEL_WEIGHTS）
同路上传（multipart 上限 4GB；经反向代理部署时代理也要放行 4GB body）。

## 4. 建实例跑起来（以 140.88 为例）

1. 「分类安装包」：程序包 + 权重包各「分发」到 140.88 节点，等 installs 子表翻 INSTALLED，
   复制权重包的 installDir。
2. 「分类服务实例」→ 新建：
   - 节点 = 140.88，端口 = 8377，baseUrl = `http://172.20.140.88:8377`；
   - 边车程序包 = 刚分发的 SIDECAR_APP 包；
   - env（关键两项）：
     - `LAYA_SLOT_MODELS` = `{"multilingual":"${PKG_DIR:<权重包id>}"}` —— 权重包 id 在
       安装包列表看，**不要手抄绝对路径**（换节点重分发路径会变，占位符不会）；
     - `LAYA_DEVICE` = `cpu` —— **与 vLLM 共卡必须**（cuda 在显存被占满时随机 500 /
       退 CPU，分诊整片降级；CPU 实测 ~2s/条零降级，见 memory「140.88 GPU 节点边车姿势」）。
   - 启动命令覆盖留空 = 默认 `venv/bin/python -m uvicorn app:app --host 0.0.0.0 --port <端口>`。
3. Drawer「启动」→ STARTING；GB 级权重加载慢，**10min 宽限**（`devmind.classify.start-grace-ms`）
   内 healthz 不通不判 UNHEALTHY。翻 RUNNING 后健康快照里能看到 loaded 槽位与 sources。

## 5. 与 CAP-55/56 的关系

- **DECISION 端点**（模型接入页）的 baseUrl 直接指向受管实例（如 `http://172.20.140.88:8377`）
  即可——决策实验室 serve 自检、连接测试、playground「DECISION 端点」通道都走它。
- 目前 224/143 环境 DECISION 端点全平台唯一指向 140.88:8377；把该边车纳入实例管控后，
  端点不用改（baseUrl 不变），起停从 SSH `pkill uvicorn` + nohup 变成页面按钮。
- playground 三通道：**受管实例直打**（诊断首选，异常原文上抛——就是要看到「连不上/
  答非所问」）/ **DECISION 端点**（带 apiKey/model 槽位钉定）/ **平台默认决策链**（含降级语义）。
  每次运行落 decision_records（capability=`classify-playground`，refId=`pg-*`），
  结果面板可跳到决策记录页查证。

## 6. 部署与配置项

| 配置（application-local.yml） | 默认 | 说明 |
|---|---|---|
| `devmind.classify.storage-dir` | `data/classify-packages` | 包存储目录；分发包部署指到数据盘 |
| `devmind.classify.health-interval-ms` | 30000 | 健康轮询间隔 |
| `devmind.classify.start-grace-ms` | 600000 | start 后健康宽限（GB 权重慢加载不误判） |
| `devmind.classify.health-timeout-seconds` | 10 | 单次 healthz 超时 |
| multipart 上限 | 4GB（application.yml 已调） | 权重包上传；反代需同步放行 |

224/143 升级：`scripts/deploy-224.sh`（或 `DEPLOY_HOST`/`DEPLOY_DIR` 覆盖到 143）一键换包即带
分类服务模块；节点 runner 升 v15 见 §2。

## 7. 排错速查

| 现象 | 先看 |
|---|---|
| start 报 409「尚未安装到节点」 | 程序包没分发到**该节点**（installs 子表按节点查）；或节点协议 <15 |
| start 报 409 提到 `${PKG_DIR}` | env 里引用的包 id 没分发到该节点 / id 抄错 |
| 实例 UNHEALTHY | Drawer 健康快照 + 节点上 `<workspace>/classify/logs/inst-<id>.log`；宽限期过了仍不通才会翻 |
| 进程没了但状态还 RUNNING | Drawer「状态对账」：按节点 pidfile 实况翻 STOPPED 并记「进程已退出」 |
| 分发一直 PENDING | 节点离线 / runner 拉包失败（节点 runner 日志有 download 行）；FAILED 可「重试」 |
| 上传 413 | 反代 body 上限没放 4GB |

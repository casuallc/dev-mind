# CAP-43 Agent 节点外网代理（Node Proxy：按功能选择走代理）

> 新增能力。缘起：worklog 推送远端 GitHub 失败——runner（Windows 服务 / LocalSystem）直连 github.com:443 被墙，
> 必须走本机代理，而 runner 侧 git 进程没有任何代理配置通道，只能手工配 repo 级 http.proxy 权宜。

## 1. 目的

给 Agent 节点增加平台级 HTTP 代理设置：管控台在节点上配一次代理地址，并可**勾选哪些功能走代理**
（scope），runner 侧对应网络操作自动经代理出口。解决：

- 办公网/GFW 环境下 runner 访问 GitHub 等外网 git 远端（worklog 远端备份、外网代码库会话）；
- 取代手工改 repo 级 git config 的权宜（配置随仓库走、平台不可见、新空间没人配）。

非目标：noProxy 绕过名单（本期靠「scope 不勾=不走代理」与代理软件自身分流覆盖）；
代理认证（userinfo）不支持——密钥不入库，本机 Clash 类代理无认证场景够用。

## 2. 功能需求

- **FR-01 节点代理配置**：`agent_nodes` 增加 `proxy_url` / `proxy_scopes`（CSV）两列，节点编辑接口
  可改；scope 枚举 `git`（runner 全部 git 网络操作）/ `claude`（claude 子进程环境）/ `exec`（exec 脚本环境），
  配了代理但 scope 空 = 默认 `git`。清空 URL = 关闭代理。
- **FR-02 URL 校验**：仅 `http(s)://host:port`；显式拒绝 userinfo（防凭证落库）与空 host；非法值 400。
- **FR-03 随帧下发（无状态）**：launch / worklog_push / exec / workspace_finalize 四类消费帧携带
  `proxy:{url,scopes[]}`；runner 中央 dispatch 收到即刷新进程级 holder，各消费点读 holder 生效，
  runner 不持久化、重启后由下一帧恢复。
- **FR-04 协议 v8 门控**：节点已配代理但 runner 协议 <8 → 服务端组帧前抛 CONFLICT 明示
  「runner 版本过旧不支持节点代理，请先升级」，不静默忽略（静默 = 又是排查黑洞）。
- **FR-05 三 scope 注入点**：git → runner 全部 git 进程命令行 `-c http.proxy=<url>`（含 clone/fetch/
  push/finalize/worklog_push，GC 的 ls-remote best-effort）；claude → 子进程 env 注入
  `HTTP_PROXY/HTTPS_PROXY`（含小写）；exec → 脚本进程 env 注入（帧 env 已有同名键不覆盖）。
- **FR-06 前端**：节点抽屉新增「外网代理」卡片——URL 输入 + scope 勾选（默认 git）+ 保存即生效
  （下一次帧下发起效，无需重启 runner）。

## 3. 关键设计

- **权威源在服务端（已定）**：协议无服务端→runner 配置推送通道，labels 的「hello 反向覆盖」模式
  不适合代理（运维动作发生在管控台）。随帧携带天然无状态、无 runner 持久化、老 runner 不被打扰。
- **一个 runner 只服务一个节点（既定事实）**：进程级 holder 无多值竞争，避免
  prepare/prepareMulti/finalize/pushWorklog 一串方法签名穿透改炸。
- **git 注入用 `-c http.proxy` 而非 env（已定）**：git 只认 http_proxy 系 env 且优先级低于 -c；
  命令行 `-c` 对子命令精确生效，且只影响 http(s) 传输，file:// 与本地操作（init/status/commit）
  天然免疫（回归测试钉死）。
- **代理凭证不支持（已定）**：URL 带 userinfo 直接 400。代理地址落库明文但不含密钥，无加密负担。
- **GC 的 ls-remote best-effort（已定）**：WorkspaceGc 是 runner 本地定时任务、无帧上下文，
  只能读 holder 的「启动以来任一帧」快照；内网 GitLab 场景本来不需要代理，可接受。

## 4. 插件化接口

无新 SPI。`AgentNodeConnector` 现有方法签名不变（代理从节点实体取，帧组装在 registry 内部完成）。

## 5. 数据模型与协议

**agent_nodes 新增列**（ddl-auto=update 自动加列，可空无默认值）：

| 列 | 类型 | 说明 |
|---|---|---|
| proxy_url | varchar(512) 可空 | 代理地址（http(s)://host:port，无 userinfo）；空 = 未配置 |
| proxy_scopes | varchar(64) 可空 | CSV，子集 {git,claude,exec}；配了代理但空 = 默认 git |

**帧协议**：`AgentProtocol.NODE_PROXY=8`，CURRENT 7→8。四类帧新增可选字段：

```json
"proxy": { "url": "http://127.0.0.1:8443", "scopes": ["git", "claude"] }
```

节点未配代理时：runner <v8 帧不携带该字段（老 runner 零感知）；runner ≥v8 恒携带显式空
`{"url":"","scopes":[]}`——runner 侧「字段缺席 = 不动 holder」，若缺席则「先配后清」
永远清不掉 runner 上已生效的代理，显式空帧即清空语义。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | /api/agent-nodes/{id} | body 扩 `{labels?, proxyUrl?, proxyScopes?}`（沿用单端点全量编辑；ADMIN） |

`AgentNodeView` 增加 `proxyUrl` / `proxyScopes` 回显。

## 7. 验收标准

1. 节点配 `http://127.0.0.1:8443`（scope=git）后，worklog 推送远端的报错从「connect timeout」
   变为 GitHub 应用层响应（403/401，证明流量过了代理）；清空配置后报错变回超时（开关即时生效）。
2. runner 协议 <8 且节点配了代理 → 推送/launch 返回明确「runner 需升级」CONFLICT，不静默。
3. 配代理后 file:// 远端与本地 git 操作全链路回归不受影响。
4. URL 带 userinfo / ssh scheme / 空 host → 400 明确提示。

## 8. 分期与 MVP 边界

- **M1（本期）**：FR-01~06 全量；scope 三枚举；无 noProxy；无代理认证；GC best-effort。
- **演进**：noProxy 主机名单（内网 GitLab 强制直连）；代理健康探测（配完即测连通性回显）；
  per-remote 代理（某代码库单独指定出口）。

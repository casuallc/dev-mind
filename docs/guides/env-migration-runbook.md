# 环境迁移 Runbook（整机换部署）

> 沉淀自 2026-10-09~10 的 172.20.140.224 → 8.135.37.194 迁移（内网 IDC → 公网云）。
> 适用：把 dev-mind 服务端整体搬到另一台机器（含换数据库、换网络形态）。

## 0. 先判拓扑：新旧环境的网络形态决定迁移量

| 形态 | 后果 |
|---|---|
| 新环境仍在内网 | 最省事：搬完即用，无需出口代理 |
| 新环境在公网、研发系统在内网（本次） | 服务端自己出访内网的功能（git 克隆/fetch、GitLab/Jira 集成 API）全部要借 **CAP-70 反向隧道** 经内网 runner 出访；执行类功能（会话/构建/部署）本就下发 runner，不受影响 |

本次拓扑：194 是公网云主机，够不到 `*.apusic.com`；解法 = 在能通内网的 Windows 办公机上再起一个 runner（`devmind-agent-194` 服务）注册到 194，配 CAP-70 出口规则 `*.apusic.com → 该节点`。

## 1. 迁移清单（少搬一样都会炸）

| 项 | 源位置 | 不搬的后果 |
|---|---|---|
| 数据库 | `pg_dump` 全库（本次 89 表） | 一切 |
| **auth.key** | `data/auth.key` | 所有集成凭证（GitLab/Jira/GitHub PAT）解密失败，等同作废 |
| 代码克隆 | `data/repositories/`（本次 446M） | DB 行仍 CLONE_READY 但磁盘无目录，fetch 报 `cannot change to ...`。**≥2026-10-10 版本有自愈**（fetch 检测 `.git` 缺失自动转全量克隆，c10f28b5），可不搬但首次抓取会变全量克隆 |
| runner 托管包 | `data/agent-runner/runner.jar` | 节点自升级拿到空/旧包 |
| config | `application-local.yml` | **不搬，按新环境重写**（库连接、server.port、DECISION 等外部端点、https 证书） |
| 节点 | — | 旧环境节点全部 disable；新 runner 重新注册（token 一次性）；项目改绑新节点 |

不需要搬：`devmind-0.1.0-SNAPSHOT/` 程序目录（dist 重新构建部署）、`dev-mind.pid`、日志。

## 2. 步骤（本次实测顺序）

```bash
# 1) 源端导库
pg_dump -h <源库> -U postgres devmind | gzip > devmind-mig.sql.gz
# 2) 目标端建库 + 灌入
sudo -u postgres createdb devmind && zcat devmind-mig.sql.gz | sudo -u postgres psql -d devmind
# 3) 搬 auth.key 与 data/（repositories 可选，见上表）
scp -P 30022 data/auth.key root@<新机>:<DEPLOY_DIR>/data/auth.key
# 4) 目标端准备 application-local.yml（库/端口/证书/外部端点）
# 5) 部署程序包（deploy 脚本参数化，复用同一脚本）
DEPLOY_HOST=root@<新机> DEPLOY_DIR=<目录> DEPLOY_PORT=<ssh端口> scripts/deploy-224.sh
# 6) 起停姿势按目标环境：systemd 托管时 deploy 脚本 stop 后必须 systemctl start
#    （scripts/dev.sh 起的进程会被 deploy 换包悬空）
# 7) 新 runner：dist 布局 + WinSW（Windows）/ systemd（Linux），注册拿 token，
#    wss 自签证书要在 xml 配 truststore 三参（ps1 install 会重写丢失）
# 8) 平台默认节点切到新 runner（agent_nodes.is_default），旧节点 disable，项目改绑
# 9) 公网形态：配 CAP-70 出口规则（内网域名 glob → 内网 runner 节点）
```

## 3. 迁移后验证清单

1. `GET /api/health` UP；登录正常（admin 数据在库内随库走）
2. 集成管理逐个「测试连接」（顺带验证 auth.key 解密链）
3. 出口规则页：隧道徽标在线（runner 协议 ≥v21）
4. 代码仓库：逐库「立即抓取」（自愈会补克隆缺失目录）
5. 起一个会话/问答冒烟（验证 runner 调度链）
6. 残留指向旧环境的地址全局排查：`DECISION` 端点、webhook 回调、书签、集成 base_url

## 4. 本次踩坑与已落地对策（复用时直接受益）

| 坑 | 根因 | 对策（已进代码） |
|---|---|---|
| Jira 同步 `I/O error ... null`，配了出口规则也没用 | **JDK HttpClient 只认 `Proxy.Type.HTTP`**，SOCKS 代理被 `retrieveProxy` 静默丢弃直连（java.net.http 模块无 SOCKS 实现） | CAP-70 增补 HTTP CONNECT 端点（127.0.0.1:18090），Java 连接器改走它（0d4354cd） |
| 隧道闲置几分钟后静默死亡、runner 永不重连 | ①JDK↔Tomcat 链路 WS ping/pong 实测不投递；②`ws.abort()` 不一定回调 listener，`closed.await()` 永挂 | 保活重构：服务端 25s 下推 `tunnel_ping` 应用帧，runner 收到过心跳才启用 75s 静默判定，abort 后手动放行闩锁重连（b20267ad） |
| 仓库抓取 `cannot change to data/repositories/...` | 迁移搬了 DB 没搬磁盘克隆，DB 行 CLONE_READY 指向不存在的目录 | fetch 自愈：`.git` 缺失自动转全量克隆（c10f28b5）；克隆失败带 stderr 尾巴进 cloneError（41578dc7） |
| GitHub 库克隆 exit=128 | 与迁移无关：PAT 失效/仓库转私有（匿名 404）。迁移后逐个测试连接能第一时间暴露 | — |
| runner 托管包版本滞后 | 服务端换包≠runner 换包；runner 包要单独上传，且自升级要 UI 点「升级」或等触发 | 流程固化：每次服务端发版若含 runner 改动，同步上传 runner 包并触发升级 |

## 5. 复用要点

- deploy 脚本已参数化（`DEPLOY_HOST`/`DEPLOY_DIR`/`DEPLOY_PORT`），143/194 与 224 共用 `scripts/deploy-224.sh`。
- 数据库共用 vs 独立是迁移前第一个要定的事（本次 194 独立 PG；224/143 曾共用 156 库，共用库的坑见共享库事故记录）。
- 公网形态务必先验证隧道再迁业务流量：隧道离线时命中规则的出访是**快速失败**（fail-visible），不是超时，排查看出口规则页徽标 + runner 日志 TunnelConnection 行。

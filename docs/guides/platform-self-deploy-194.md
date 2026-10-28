# 194 平台自举部署指南（dev-mind 部署 dev-mind）

194（https://8.135.37.194:38880）上的 dev-mind 平台已登记对自身的构建（CAP-08）与部署（CAP-09）配置：
项目在平台上即可一键把 master 构建并正式部署到 194 本机。2026-10-19 首次走通全链路
（构建 #19 → 部署 #15，master@ad2ae530）。

## 配置清单（全部在自项目 `dev-mind` id=lj606ymb 下）

| 项 | 值 |
|---|---|
| 构建配置 | executor=AGENT，agentNodeId=4（kingdee-win-194），并发 1 |
| 构建步骤 | ① 前端构建（node PATH + npm ci + npm run build，npm-cache 复用）② dist 打包（JDK21 + maven，`bash scripts/build-dist.sh --skip-frontend`，末行 `echo "artifact=$(cygpath -a ...)"` 登记**绝对 MSYS 路径**） |
| 脚本模板 | `dm_backup`（远端备份，echo backup= 供捕获）/ `dm_deploy_stage`（scp 上传 + systemd-run 触发远端自部署）/ `dm_rollback_stage`（触发远端回滚）/ `dm_probe`（SSH 冒烟） |
| 环境 | PROD（节点 ["4"]，变量 DEPLOY_HOST=root@8.135.37.194、DEPLOY_PORT=30022、DEPLOY_DIR=/home/liuchangqing/dev-mind、HEALTH_URL=https://127.0.0.1:38880/api/health） |
| 部署配置 | steps=[远端备份(backup/dm_backup), 上传并触发自部署(deploy/dm_deploy_stage)]；rollbackSteps=[触发远端回滚(dm_rollback_stage)] |
| 节点 4 代理 | proxyUrl=http://127.0.0.1:8443，scopes=git,exec（本机 GitHub 直连间歇性超时，必须走代理） |

runner-194（WinSW 服务 devmind-agent-194，LocalSystem）的 `agent.properties` 已配
`execShell=Git bash` 与 `execAllowlist`（bash/ssh/scp/systemctl/systemd-run/tar/mv/cp/rm/…）。

## 操作路径

1. 推送目标 commit 到 GitHub master。
2. 项目页触发构建（或 `POST /api/projects/lj606ymb/builds`，**显式带 commit SHA**——服务端 fetch 失败会静默落到旧 HEAD）。
3. 构建 SUCCESS 且 artifactRef 为 `/c/...tar.gz` 后，发起部署（环境 PROD + 该构建单）并执行。
4. 部署单 SUCCESS 只表示「备份+上传+触发完成」。约 1~2 分钟后核验真实结果：
   - `curl -sk https://8.135.37.194:38880/api/health` 回 UP；
   - `ssh -p 30022 root@8.135.37.194 'cat /home/liuchangqing/dev-mind/.deploy-incoming/result'` 应为 `OK`；
   - FAIL 时远端已自动从 backup 回滚并拉起旧版，查 `journalctl -u 'devmind-selfdeploy*'` 与 `logs/`。

## 为什么部署计划不直接停服换包（平台部署平台自身的约束）

DeploymentService 无断点续跑：`systemctl stop dev-mind` 会杀掉编排 JVM 本身 → 部署单卡 RUNNING
两小时后被判僵 FAILED + P0 误报，且幂等规则挡住同 build 重试。因此部署计划只做到
「备份 + scp 上传 + `systemd-run --collect` 触发远端 self-deploy.sh 后立即返回」；
stop/解包/换 bin·libs·ui·runner·lab·config（**跳过 application-local.yml**）/start/健康轮询 120s/
失败自动回滚全部在远端 detached 脚本内自闭环（逻辑蒸馏自 `scripts/deploy-common.sh`）。

## 踩过的坑（后续维护必读）

- **LocalSystem ssh**：必须 `export HOME=/c/Users/kingdee` **且** PATH 前置 `/c/Program Files/Git/usr/bin`
  ——System32 OpenSSH 不认 MSYS 形态 HOME 会报 Host key verification failed；模板再显式传
  `-o UserKnownHostsFile=$HOME/.ssh/known_hosts -i $HOME/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new`（SSHOPT 变量）。
- **execAllowlist 逐行校验含 heredoc 终止符行**：终止符取名 `bash_eof` 之类以允许前缀开头（首 token startsWith 匹配），或整段避免 heredoc。
- **行首赋值不被豁免**：`TS=$(date)` 会被拦，一律 `export TS=$(date)`。
- **artifact 必须绝对 MSYS 路径**（`cygpath -a`）：相对路径在部署步骤 cwd（runner workDir）下找不到；
  `C:/...` 会被 scp 把盘符当主机名。
- **模板 render 只替换已声明 params**：`${artifact}`/`${backup}`/环境变量都要在模板 params 里声明（required=false）。
- 远端 self-deploy 成功后会把包归档为 `devmind-selfdeploy-<ts>.tar.gz` 并保留最近 2 个包、5 份 backup。
- 同 project+节点+build 的重复部署被幂等挡 → 重建部署单传 `force:true`。

## 排错入口

| 现象 | 查 |
|---|---|
| 构建 fetch 失败 | 节点 4 代理是否还在（`curl -x http://127.0.0.1:8443 https://github.com`） |
| 部署步骤 allowlist 报错 | runner-194 `agent.properties` execAllowlist；改后须重启服务（平台强制升级） |
| 部署单 SUCCESS 但版本没换 | `.deploy-incoming/result`、`journalctl -u 'devmind-selfdeploy*'` |
| 平台起不来 | `journalctl -u dev-mind`、`logs/dev-mind.log`；手工回滚=`/api/deployments` 建单用 dm_rollback_stage + backup 路径 |

## 统一 E2E 套件接入

复用 CAP-69 脚本套件，无需修改平台后端。标准见 [E2E 规范](../core/e2e-testing-standard.md)。194 共享实例只登记 smoke，写入回归在开发/构建隔离实例运行。

1. 将统一入口代码提交并推送到套件配置的分支（默认 master）；节点 4 的独立套件工作区会从 Git 拉代码，本地未推送代码不可见。
2. 节点需可用 Python 3.10+、Git Bash 和仓库拉取权限。LocalSystem 找不到 Python 时，注册前提供 `E2E_PYTHON` 为节点上 Python 可执行文件路径；wrapper 支持带空格路径。
3. 预览接入配置（不写平台、不含凭据）：

   ```bash
   python tests/e2e/register.py --base https://8.135.37.194:38880/api --project lj606ymb --node 4 --insecure
   ```

4. 通过进程环境注入 `E2E_USER` / `E2E_PASSWORD`（建议专用测试账号，仅需读取权限）。登记套件需要管理员，若该账号无管理权限，另用 `E2E_TOKEN` 提供管理员令牌。凭据不要写命令行或提交到文件。加 `--apply` 重跑注册命令，按固定套件名查重，已有则更新，不重复建单。密码作为 `secret=true` 的套件变量保存，响应脱敏；每次冒烟登录取短期 token，避免保存一个很快过期的 token 当长期配置。
5. 在 dev-mind 项目 → 测试中运行 `dev-mind / E2E smoke (standard v1)`。命令为 `bash scripts/e2e.sh --suite smoke --insecure`，JUnit 路径 `tmp/e2e/junit.xml`，超时 300 秒，工作区 key `devmind-e2e-smoke-v1`。平台自动回收报告并呈现逐用例结果；失败查看日志。

194 自部署步骤异步触发后先等健康与 `.deploy-incoming/result=OK`，再人工触发该冒烟套件。此接入不修改现有部署计划，也不启用所有测试套件的自动回归开关，以免把未审核的历史套件一并触发。

验收：首次手动运行必须出现 5 条用例、无 failure/error，且报告不含密码/token；不可仅凭脚本命令退出 0 判断接入成功。当前 5 条覆盖健康、匿名访问拒绝、身份、项目列表、更新日志；业务全链路/UI 仍按规范继续迁移。

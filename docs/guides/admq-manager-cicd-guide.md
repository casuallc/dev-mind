# dev-mind 使用说明：admq-manager 构建 → 部署 → 发布（runner 节点版）

目标：admq-manager 已接入 172.20.140.143:8088 的 dev-mind（2026-09-10 完成），
从页面一键完成 **在节点 build-224 上构建（build.sh 全量打包）→ 部署到 224 本机 /apusic/admq → 发布到 Nexus**。

> 与 ctyunmanager 指南的差异：CAP-36 起 SSH/服务器适配器已下线，构建/部署/发版全部走
> **agent-runner 节点 exec 帧**（executor=AGENT），不再需要登记 SSH 服务器与私钥。

## 0. 已配置内容一览

| 项 | 值 |
|---|---|
| 项目 | `admq-manager`（id `k587rts7`，CLONE 模式接 GitLab 集成，默认分支 develop） |
| 默认 AI 节点 | 节点 2（会话/问答）；构建/部署/测试/发版 → 节点 1（build-224，aarch64 Linux） |
| 命令模板 | `admq_backup` / `admq_deploy` / `admq_start` / `admq_health` / `admq_rollback`（deploy）；`admq_nexus_push`（release） |
| 环境 | `TEST`：节点 1，变量 `APP_DIR=/apusic/admq`、`DIST_DIR=/apusic/admq/dist` |
| 构建配置 | executor=AGENT → 节点 1；2 步：build.sh 全量打包 → 归档制品并登记 artifact |
| 部署计划 | 备份 → 部署（解压）→ 启动（manager stop/start）→ 健康检查（http 12305），失败自动回滚 |
| 发版配置 | executor=AGENT → 节点 1；模板 `admq_nexus_push` 推 Nexus `file-server /admq/v2.4`，版本规则 2.0.7 |

链路：

```
「构建」Tab 触发 → runner 节点 1 clone 工作区（token 随帧下发）→
  步骤1 bash build.sh（后端 mvn install -Prelease + 前端 vite build + 三架构 JDK/Prometheus 下载打包 + 升级包）
  步骤2 拷贝 tar.gz/sha512/升级包到 /apusic/admq/dist/ 并 echo artifact=admq-manager-V2.0.7.<日期>-<uname -m>.tar.gz

「部署」Tab 创建部署（环境 TEST + 选构建）→ 节点 1 本机执行模板：
  备份 → tar 解压到 /apusic/admq → manager 重启 → 12305 健康检查；任一步失败自动回滚

「发版配置」Tab 新建发版 → 节点 1 执行 admq_nexus_push：
  按 artifact 前缀把三架构包 + sha512 + 升级包 curl 到 Nexus；成功自动打 v<x.y.z> tag 并 push GitLab Release
```

## 1. 日常使用

- **一键构建**：工作台 → admq-manager →「构建」Tab → 触发构建（分支留空 = develop）。全量约 15 分钟
  （mvn 38 模块 + npm + 三架构打包；`.build-cache` 在节点上跨构建复用，二次构建明显更快）。
- **一键部署**：「部署」Tab → 创建部署（环境 TEST + 选成功构建）→ 执行 → 看每步实时日志。
- **发布**：项目设置 →「发版配置」Tab → 新建发版（版本留空按规则 patch+1）→ 创建并执行。
  发版版本号只影响平台记录与 git tag，包文件名取自 pom 版本 + 构建日期（同 push.sh 语义）。

## 2. 节点机（build-224）维护项

| 文件/配置 | 说明 |
|---|---|
| `/apusic/dev-mind-agent/config/agent.properties` | `execAllowlist` 必须含 `bash,cp,rm,tar,curl,sleep,mkdir,ln,./manager`（已配）；改后 `systemctl restart devmind-agent` |
| `/apusic/dev-mind-agent/workspaces/_default` | 无 repo 块的 exec（部署/发版）cwd，必须存在（已建） |
| `/home/jdk-21.0.7+6`、`/opt/apache-maven-3.9.9` | 构建步骤里显式 export PATH（runner 进程 PATH 极简无 java/mvn） |
| `/apusic/admq/.nexus-auth` | **发版前提**：手动创建，内容一行 `NEXUS_AUTH=user:password`（凭据不落平台库） |
| `/etc/hosts` | `172.18.100.10 gitlab.apusic.com nexus.apusic.com`（已配；140.143 同配） |

## 3. 踩坑记录（接入时实际踩过）

1. **git-commit-id-plugin 4.0.0（JGit）读不了 worktree**：runner 构建工作区是 `git worktree --detach`，
   插件报 `Missing unknown <sha>`。构建步骤注入 `MAVEN_ARGS=-Dmaven.gitcommitid.nativegit=true` 解决
   （不能用 `-Dmaven.gitcommitid.skip=true`——admq-core 的 `java-templates/AdmqManagerVersion.java`
   靠 `${git.commit.id.abbrev}` 过滤，skip 会把占位符原样编译进 class）。
2. **build.sh 硬编码目录名 `admq-manager`**（`cd ..` 后按名引用）：runner 工作区目录名是 `build-<id>`，
   构建步骤 1 先 `ln -sfn "$PWD" ../admq-manager` 建软链再跑 build.sh。
3. **模板渲染只替换已声明参数**：平台内置注入的 `${artifact}`/`${backup}`/`${repository}`
   也必须在模板 params 里声明（required=false），否则字面残留被 bash 展开成空串（tar 读到目录名报错）。
4. **模板内赋值行首不豁免 allowlist**：`ts=$(date ...)` 行首会被 execAllowlist 拦，行首加 `export` 即可。
5. **PG 库 CAP-07 残留列**：`deployments.server_id NOT NULL` 导致建部署单 500，
   已 `ALTER TABLE deployments ALTER COLUMN server_id DROP NOT NULL`（ddl-auto=update 不删旧列）。
6. **节点 labels 与真实架构可能不符**：224 实为 aarch64（labels 手填 x86_64），artifact 登记用 `uname -m` 动态取值。
7. **升级版本号要同步多处**：2.0.7 硬编码在 build.sh、构建步骤 2、admq_deploy/admq_rollback 模板
   （安装目录名 `admq-manager-V2.0.7`）。版本升级时全部要改。

## 4. 常见问题

| 现象 | 排查 |
|---|---|
| 触发构建 500「Could not resolve host: gitlab.apusic.com」 | 140.143 /etc/hosts 缺 172.18.100.10 映射（见上表） |
| 触发构建 500「could not read Username」 | 项目未绑定 GitLab 集成（项目设置 → 集成，绑 GitLab 实例） |
| 构建瞬间失败「命令不在 execAllowlist 白名单」 | 报错会列出允许前缀；把缺的第一 token 加进 224 agent.properties 并重启 runner |
| 部署步骤报 `Cannot run program "bash" (in directory ...)` | runner workDir 目录不存在，`mkdir -p /apusic/dev-mind-agent/workspaces/_default` |
| 发版失败「缺少 Nexus 凭据」 | 在 224 创建 `/apusic/admq/.nexus-auth`（见上表） |
| 发版成功但 GitLab 没 Release | 项目已绑 GitLab 集成即会自动 push tag + 建 Release；查项目「集成调用」记录 |

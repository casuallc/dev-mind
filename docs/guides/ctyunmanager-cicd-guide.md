# dev-mind 使用说明：ctyun-manager 自动构建 → 部署 → 发布

目标：在 dev-mind 里配置一次，之后从页面一键完成
**本地构建（build.ps1）→ 上传产物到 172.20.140.224 → 远程部署到 /apusic/ctyun → 发布到 Nexus（push.sh）**。

> 前置：已用 `scripts/dev.ps1` 启动 dev-mind 前后端，并用 ADMIN 账号登录 http://localhost:5173。
> 配置类操作都在左侧菜单「后台管理」（/admin）；执行类操作在工作台项目页（/projects/:id）。

> 💡 也可以不用手配：后台「项目管理」页点「AI 智能接入」，把项目情况（仓库路径/构建脚本/部署服务器/部署目录）描述一遍，
> 平台会启动一个全自动会话通过开放 API（CAP-20）完成下面的全部配置并触发一次构建验证——跳到会话页可实时观看。

---

## 0. 整体链路（先看懂再配）

```
工作台「构建」Tab 触发构建
  └─ 本机 Git Bash 依次执行构建步骤：
       1) powershell -File build.ps1        → 产出 ctyun-distribution/target/ctyun-manager-1.0.0-SNAPSHOT.tar.gz
       2) scp 产物到 224:/apusic/ctyun/     → 并 echo artifact=... 登记产物（部署时按它选构建）

工作台「部署」Tab 创建并执行部署
  └─ SSH 到 224，按部署计划逐步执行白名单命令模板：
       备份 → 部署（解压）→ 启动（manager stop/start）→ 健康检查
       任一步失败自动按回滚步骤回滚

后台「发版配置」Tab 创建并执行发版
  └─ 本机执行发布模板（./push.sh）→ 推送 tar.gz + sha512 到 Nexus file-server /admq/ctyun
     → 成功后自动打 git tag v<version>（绑定 GitLab 集成时同步 push tag + 建 Release，可选）
```

关键环节：**远程部署只能执行「命令模板」白名单里的脚本**（CAP-07 安全设计，不允许页面临时拼命令），所以第 3 步建模板是核心工作量，一次配置长期复用。

---

## 1. 注册项目（后台 → 项目管理）

后台管理 → 项目管理 →「新建项目」：

| 字段 | 填 |
|---|---|
| 名称 | `ctyun-manager` |
| 仓库路径 | `D:\apusic\ctyunmanager`（⚠️ 必须含 `.git`；`D:\apusic\dev-test` 目前是空仓库，不能做主库） |
| 默认分支 | `master`（按实际改） |
| 标签 | 可选，如 `java,vue` |
| 描述 | 可选 |
| 部署成功后自动回归 | 先不开（配好测试套件后再开） |

保存后得到 8 位随机项目 id（如 `a1b2c3d4`），列表点「设置」进入项目配置页。

> 如果你确实想把 dev-test 当项目壳：path 填 dev-test，然后到「仓库」Tab 添加 ctyunmanager 并「设为主库」。构建/会话都只用主库。一般没必要，直接注册 ctyunmanager 即可。

## 2. 登记部署服务器（项目配置 → 服务器 Tab）

项目配置页（/admin/projects/:id）→「服务器」Tab →「添加服务器」：

| 字段 | 填 |
|---|---|
| 名称 | `测试机-224` |
| 环境 | `test` |
| 接入方式 | `ssh` |
| 连接配置（JSON） | 见下 |
| 能力标签 | 勾 `deploy`、`release`（如还想远程构建再勾 `build`） |
| 启用 | 开 |

accessConfig（你的 224 是 key 免密，把本机私钥内容贴进来）：

```json
{
  "host": "172.20.140.224",
  "port": 22,
  "username": "root",
  "authType": "key",
  "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n...（~/.ssh/id_rsa 全文，\n 换行）\n-----END OPENSSH PRIVATE KEY-----"
}
```

- 敏感字段会 AES-GCM 加密落库，只显示掩码。
- 若服务器允许密码登录也可以 `"authType":"password","password":"..."`。
- **验证**：保存后到 后台管理 → 服务器运维 → 找到该服务器 →「测试连接」，必须通了再继续。

## 3. 建命令模板（后台 → 服务器运维 → 命令模板 Tab）

部署的每一步 = 在 224 上执行一个白名单模板。模板正文是 shell 脚本，`${参数名}` 占位符在执行时替换（可用内置参数 `${backup}` 和环境变量，见第 4 步）。逐个点「新建模板」：

**① `dep_backup`（备份，allowed 勾 deploy）**

```bash
set -e
cd ${APP_DIR}
ts=$(date +%Y%m%d%H%M%S)
cp -a ctyun-manager-1.0.0-SNAPSHOT "backup-$ts"
echo "backup=${APP_DIR}/backup-$ts"
```

> 最后一行的 `backup=` 会被平台识别为备份引用，回滚步骤用 `${backup}` 引用它。

**② `dep_deploy`（解压新版，allowed 勾 deploy）**

```bash
set -e
cd ${APP_DIR}
rm -rf ctyun-manager-1.0.0-SNAPSHOT
tar xzf "${PKG}"
```

**③ `dep_start`（重启服务，allowed 勾 deploy）**

```bash
set -e
cd ${APP_DIR}/ctyun-manager-1.0.0-SNAPSHOT/bin
./manager stop || true
sleep 2
./manager start
```

**④ `dep_health`（健康检查，allowed 勾 deploy）**——按实际端口/接口改：

```bash
sleep 5
curl -sf -o /dev/null http://127.0.0.1:8080/ || { echo "health check FAILED"; exit 1; }
```

**⑤ `dep_rollback`（回滚，allowed 勾 deploy）**

```bash
set -e
cd ${APP_DIR}
rm -rf ctyun-manager-1.0.0-SNAPSHOT
cp -a "${backup}" ctyun-manager-1.0.0-SNAPSHOT
cd ctyun-manager-1.0.0-SNAPSHOT/bin
./manager stop || true
sleep 2
./manager start
```

**⑥ `nexus_push`（发布到 Nexus，allowed 勾 release）**——发版在本机（Git Bash）主库目录执行，直接调你现成的脚本：

```bash
./push.sh
```

## 4. 建环境（项目配置 → 环境 Tab）

「添加环境」：

| 字段 | 填 |
|---|---|
| 名称 | `TEST` |
| 服务器 | 勾 `测试机-224` |
| 变量（每行 KEY=VALUE） | 见下 |

```
APP_DIR=/apusic/ctyun
PKG=ctyun-manager-1.0.0-SNAPSHOT.tar.gz
```

这两个变量会注入部署的每一步，对应上面模板里的 `${APP_DIR}`、`${PKG}`。以后换部署目录只改这里，不动模板。

## 5. 构建配置（项目配置 → 构建配置 Tab）

「添加步骤」两步（顺序执行，单步默认超时 30 分钟）：

| # | 名称 | 命令 | 执行目录 | 位置 |
|---|---|---|---|---|
| 1 | 编译打包 | `powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -SkipTests` | 留空（仓库根） | LOCAL |
| 2 | 上传产物并登记 | 见下 | 留空 | LOCAL |

步骤 2 命令（Git Bash 语法，scp 走你本机已配好的免密）：

```bash
scp ctyun-distribution/target/ctyun-manager-*.tar.gz root@172.20.140.224:/apusic/ctyun/
echo "artifact=ctyun-manager-1.0.0-SNAPSHOT.tar.gz"
```

- 平台执行构建用的是 **Git Bash 不是 PowerShell**，所以 ps1 要用 `powershell -File` 包一层。
- `artifact=` 这行是约定：日志里最后一个 `artifact=`/`artifact:` 行会登记为构建产物，**没有它创建部署时选不到构建**。
- build.ps1 要求 JDK 21，确认 dev-mind 后端进程的环境里 JAVA_HOME 或自动探测可用（本机跑过 build.ps1 就行）。

## 6. 发版配置（项目配置 → 发版配置 Tab）

| 字段 | 填 |
|---|---|
| Nexus 仓库 | `file-server` |
| 推送脚本模板 | `nexus_push`（第 3 步⑥） |
| 版本规则 | `1.0.0`（发版时版本留空则自动 +1） |
| 执行位置 | LOCAL（push.sh 在本机 Git Bash 跑，curl 直传 Nexus） |

> 注意：push.sh 推的是 pom 里版本号命名的包（1.0.0-SNAPSHOT），发版填的 version 只影响 git tag 和平台记录，不改包文件名。tag 会打在主库（ctyunmanager）上，如 `v1.0.0`。

---

## 7. 日常使用（工作台）

### 一键构建

工作台 → 项目 → 进入 ctyun-manager →「构建」Tab：

1. 首次先在「构建配置」卡片保存：执行位置 = 本机，并发数 1
2. 「触发构建」卡片：分支留空（当前分支）→「触发构建」
3. 构建历史里点「日志」实时看输出（WebSocket 流式），成功后「产物」列显示登记的包名

### 一键部署

「部署」Tab：

1. 首次配「部署计划配置」卡片：
   - 部署步骤依次添加：`备份(dep_backup)` → `部署(dep_deploy)` → `启动(dep_start)` → `健康检查(dep_health)`，类型分别选 backup / deploy / start / health
   - 回滚步骤添加：`回滚(dep_rollback)`，类型 deploy
2. 「创建部署」卡片：环境选 `TEST`（服务器/变量自动带入）、构建选刚成功的那个 →「创建部署」
3. 部署记录点「详情」→「执行」→ 实时看每步日志
4. 任一步失败会**自动回滚**（dep_rollback 用备份目录恢复并重启）

### 发布到 Nexus

后台管理 → 项目管理 → 设置 →「发版配置」Tab →「新建发版」：版本留空（按规则生成）→「创建并执行」→ 详情里看 push.sh 输出，成功后 Nexus `file-server /admq/ctyun` 能看到新包，主库多了 `v<x.y.z>` tag。

---

## 8. 常见问题

| 现象 | 排查 |
|---|---|
| 构建瞬间失败 exit=127，PowerShell 报「-File 参数不接受实际参数 build.ps1」 | 项目注册的仓库路径指错了（比如指到空壳 dev-test），构建在项目 path 目录下执行；改项目路径为 `D:\apusic\ctyunmanager` |
| 构建步骤 1 秒失败，日志乱码/找不到 powershell | 平台用 Git Bash 执行；确认命令写的是 `powershell -File build.ps1` 不是直接 `.\build.ps1` |
| 构建成功但创建部署选不到构建 | 构建日志里没有 `artifact=` 行，检查步骤 2 的 echo |
| 部署执行报「模板不存在/不允许」 | 模板 code 拼写、模板的 allowed 能力是否含 deploy、模板 projectId 是否为本项目 |
| SSH 测试连接失败 | 私钥内容粘贴时换行必须是 `\n`；或先用密码认证验证网络 |
| scp 步骤卡住 | 首次连接有 host key 确认，先在 Git Bash 手动 `ssh root@172.20.140.224` 接受一次指纹 |
| 健康检查失败但服务正常 | dep_health 里的端口/路径换成真实接口 |
| 发版成功但 GitLab 没 Release | CAP-18 是可选项，需在后台登记 GitLab Integration 并绑定项目；不绑不影响发版本身 |

## 9. 可选增强（以后再说）

- **自动回归**：项目编辑里打开「部署成功后自动回归」，配好测试套件（CAP-10）后，每次部署成功自动跑回归
- **GitLab 集成（CAP-18）**：后台登记 GitLab（base_url + PAT）并绑定项目后，发版打 tag 会自动 push 并创建 GitLab Release；工作单元分支也能一键 push + 建 MR
- **需求主线**：这个项目注册后，/projects/:id 的需求列表可以直接走「需求 → AI 分析/方案 → 工作单元 → 起会话」的研发流程，与构建/部署/发版记录自动关联

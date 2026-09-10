# Agent Runner 服务化部署指南（Linux systemd / Windows 服务）

分发包（`devmind-<version>.tar.gz`）自带 `runner/devmind-agent-runner.jar`，两平台均可注册为系统服务：开机自启、崩溃自动拉起（`Restart=on-failure` / `<onfailure action="restart"/>`）。

前置：解开分发包 → 参考 `config/agent.properties.example` 生成 `config/agent.properties` 并填入 token（后台 → Agent 节点 新建节点复制）。

## Linux（systemd）

```bash
sudo bin/dev-mind-agent install        # 写 /etc/systemd/system/dev-mind-agent.service + daemon-reload
systemctl enable --now dev-mind-agent  # 开机自启 + 立即启动
systemctl status dev-mind-agent
sudo bin/dev-mind-agent uninstall      # disable --now + 删 unit
```

- unit 以 root 身份、`bin/dev-mind-agent run` 前台模式运行；Java 解析顺序 `APUSIC_JAVA_HOME > JAVA_HOME > PATH`（必须 21+）。
- unit 名默认 `dev-mind-agent`；已有手工 unit（如线上 `devmind-agent`）可用 `DEVMIND_SERVICE_NAME=devmind-agent sudo -E bin/dev-mind-agent install` 接管。
- 非 root 执行 `install` 会把 unit 内容打印出来供手工安装。

## Windows（WinSW）

**管理员** PowerShell 中执行 `bin\dev-mind-agent.ps1 <command>`：

```powershell
.\bin\dev-mind-agent.ps1 install     # 注册服务 devmind-agent（首次自动下载 WinSW）
.\bin\dev-mind-agent.ps1 start       # 等价 Start-Service devmind-agent
.\bin\dev-mind-agent.ps1 restart
.\bin\dev-mind-agent.ps1 status
.\bin\dev-mind-agent.ps1 uninstall   # 停止并移除服务
.\bin\dev-mind-agent.ps1 run         # 前台运行（调试）
```

- 服务以 LocalSystem 运行，崩溃 5 秒后自动重启；日志由 WinSW 滚动写到 `logs/`。
- **离线/内网**：先下载 [WinSW.NET461.exe（v2.12.0）](https://github.com/winsw/winsw/releases/tag/v2.12.0) 放到 `bin/` 并改名 `devmind-agent.exe`，再执行 install；或用 `-WinSwUrl <内网地址>` / 环境变量 `WINSW_URL` 指定下载源。v2 系只需 .NET Framework 4.6.1+（Win10/Server 2016+ 自带），无需额外运行时。
- java 以**绝对路径**写入服务配置（LocalSystem 的 PATH 不含用户环境变量），解析顺序同 Linux。
- 服务名可用环境变量 `DEVMIND_SERVICE_NAME` 覆盖。

## 注意事项

- **停服是直接终止 java 进程**（Windows 无优雅停机通道），不影响会话数据——runner 重启时扫描 `sessions/` 目录做现场对账：存活 claude 进程 reattach 挂回、孤儿进程整树回收（CAP-34）。
- **runner jar 升级后必须重启服务**（`systemctl restart` / `restart`）——运行中的 jar 被覆盖会新旧类混装，典型症状 409 ack 超时。
- 不想注册服务时，`bin/dev-mind-agent start|stop|restart|status`（nohup + pid 文件）依旧可用，两条路径互不冲突但不要混用同一实例。

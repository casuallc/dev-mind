Dev-Mind 运行时数据目录。

- devmind.mv.db        H2 文件库（首次启动自动创建）
- auth.key             主密钥：JWT 签名 + 各 enc1: 加密域（集成/模型/书签凭证）的派生源。
                       安装包内置一份默认随机值（构建机本地生成，非公开常量）；
                       缺失时应用首次启动也会自动生成。
- dev-mind.pid         进程 PID（bin/dev-mind 写入）
- key-backup-*/        rotate-key 轮换前的旧密钥备份（确认新实例正常后可删）

轮换密钥：bin/dev-mind stop && bin/dev-mind rotate-key && bin/dev-mind start
  后果：旧登录态全部失效，库中所有 enc1: 密文随旧密钥作废（集成 token、模型 apiKey、
  书签口令等需重新录入）。systemd 托管时先 systemctl stop dev-mind。

删除整个 data/ 等于重置为全新实例（密钥重新生成，已有登录态与加密凭证全部失效）。
备份实例 = 备份本目录 + config/。

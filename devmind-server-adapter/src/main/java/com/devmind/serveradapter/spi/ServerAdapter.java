package com.devmind.serveradapter.spi;

/**
 * CAP-07 FR-02 统一服务器适配 SPI。
 * 各实现按 accessType 注册（ssh/http/…），由 ServerAdapterRegistry 路由；上层只依赖本接口。
 * 注意：凭证已在 ServerTarget.config 中解密，适配器不得把配置/密钥写入审计或日志。
 */
public interface ServerAdapter {

    /** 本实现支持的 accessType（ssh / http） */
    String supportedType();

    ConnectResult connectTest(ServerTarget target, long timeoutMs);

    /** 执行已渲染的脚本（模板白名单在 Service 层已校验） */
    ExecResult execute(ServerTarget target, String script, long timeoutMs);

    void upload(ServerTarget target, String localPath, String remotePath, long timeoutMs);

    /** 返回远端文件内容（文本），失败抛 DevMindException */
    String download(ServerTarget target, String remotePath, long timeoutMs);

    HealthResult healthCheck(ServerTarget target, HealthCheckConfig cfg, long timeoutMs);
}

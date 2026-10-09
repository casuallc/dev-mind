package com.devmind.agent.config;

import com.devmind.common.egress.TunnelFrame;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.egress.* — CAP-70 服务端出口反向隧道配置。主类 @ConfigurationPropertiesScan 全局扫描。
 */
@ConfigurationProperties(prefix = "devmind.egress")
public class EgressProperties {

    /** 内嵌 SOCKS5 server 开关（false = 功能整体关闭，规则表仍在但不提供出口） */
    private boolean enabled = true;

    /** SOCKS5 监听端口（只绑 127.0.0.1，消费方全是同机进程） */
    private int socksPort = 18089;

    /** 每流每方向在途窗口字节数（默认 256KB，CAP-70 FR-01） */
    private int windowBytes = TunnelFrame.DEFAULT_WINDOW_BYTES;

    /** OPEN 帧等待 OPEN_ACK 超时（毫秒） */
    private long openTimeoutMs = 30_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSocksPort() { return socksPort; }
    public void setSocksPort(int socksPort) { this.socksPort = socksPort; }
    public int getWindowBytes() { return windowBytes; }
    public void setWindowBytes(int windowBytes) { this.windowBytes = windowBytes; }
    public long getOpenTimeoutMs() { return openTimeoutMs; }
    public void setOpenTimeoutMs(long openTimeoutMs) { this.openTimeoutMs = openTimeoutMs; }
}

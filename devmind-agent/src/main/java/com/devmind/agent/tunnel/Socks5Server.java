package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.common.egress.EgressHostMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * CAP-70 FR-02：内嵌 SOCKS5 server——<b>只绑 127.0.0.1</b>（禁 0.0.0.0）、无认证
 * （消费方全是同机进程 git/JDK HttpClient）。
 *
 * <p>CONNECT 处理：目标 host 查 {@link EgressRuleService} 规则表 →
 * 命中且隧道在线 → 经隧道 OPEN → 双向 relay（{@link ServerTunnelStream}）；
 * 命中但不可用 → 拒绝（rep=4/5）并记日志（不挂起等待）；
 * <b>未命中 → 拒绝（rep=2 not-allowed）</b>——SOCKS 端点不放行任何未配规则的目标，
 * 规则表即白名单。DNS 语义：客户端一律 socks5h（主机名由 runner 侧解析）。</p>
 */
@Component
public class Socks5Server implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Socks5Server.class);

    private static final int REP_OK = 0;
    private static final int REP_NOT_ALLOWED = 2;
    private static final int REP_HOST_UNREACHABLE = 4;
    private static final int REP_CMD_NOT_SUPPORTED = 7;

    private final EgressProperties props;
    private final EgressRuleService ruleService;
    private final AgentTunnelRegistry tunnelRegistry;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public Socks5Server(EgressProperties props, EgressRuleService ruleService,
                        AgentTunnelRegistry tunnelRegistry) {
        this.props = props;
        this.ruleService = ruleService;
        this.tunnelRegistry = tunnelRegistry;
    }

    @Override
    public void start() {
        if (!props.isEnabled()) {
            log.info("CAP-70 出口隧道已禁用（devmind.egress.enabled=false）");
            return;
        }
        running = true;
        Thread.ofVirtual().name("egress-socks5-accept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        try {
            // 只绑 127.0.0.1（FR-02：无认证，绝不对外暴露）
            serverSocket = new ServerSocket(props.getSocksPort(), 50, InetAddress.getByName("127.0.0.1"));
            log.info("CAP-70 出口隧道 SOCKS5 端点已监听 127.0.0.1:{}", props.getSocksPort());
            while (running) {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("egress-socks5-conn").start(() -> handle(client));
            }
        } catch (IOException e) {
            if (running) {
                log.error("SOCKS5 端点异常退出: {}", e.toString());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String host = handshake(in, out, client);
            if (host == null) {
                return;
            }
            int port = in.readUnsignedShort();

            Optional<EgressRuleEntity> route = ruleService.findRoute(host);
            if (route.isEmpty()) {
                // FR-02：未命中规则 = 拒绝（白名单语义，不放行任何未配目标出隧道）
                reply(out, REP_NOT_ALLOWED);
                log.info("SOCKS5 拒绝（未命中出口规则）: {}:{}", host, port);
                closeQuietly(client);
                return;
            }
            EgressRuleEntity rule = route.get();
            try {
                ServerTunnelStream stream = tunnelRegistry.openStream(rule.getNodeId(), host, port, client);
                reply(out, REP_OK);
                stream.start();
                // 此后 socket 归流所有，流负责关闭
            } catch (Exception e) {
                // FR-07：命中但不可用 = 拒绝 + 记日志（不挂起等待、不静默直连）
                reply(out, REP_HOST_UNREACHABLE);
                log.warn("SOCKS5 拒绝（规则「{}」命中但出口不可用）: {}:{} — {}",
                        rule.getHostPattern(), host, port, e.getMessage());
                closeQuietly(client);
            }
        } catch (Exception e) {
            log.debug("SOCKS5 连接处理失败: {}", e.toString());
            closeQuietly(client);
        }
    }

    /** 握手 + 请求解析；成功返回目标 host（调用方继续读 port），失败已回复并关连接返回 null */
    private String handshake(DataInputStream in, OutputStream out, Socket client) throws IOException {
        int ver = in.readUnsignedByte();
        int nmethods = in.readUnsignedByte();
        in.skipBytes(nmethods);
        if (ver != 5) {
            closeQuietly(client);
            return null;
        }
        out.write(new byte[]{5, 0}); // 无认证
        out.flush();

        if (in.readUnsignedByte() != 5) {
            closeQuietly(client);
            return null;
        }
        int cmd = in.readUnsignedByte();
        in.readUnsignedByte(); // RSV
        int atyp = in.readUnsignedByte();
        String host = switch (atyp) {
            case 1 -> {
                byte[] b = in.readNBytes(4);
                yield (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
            }
            case 3 -> {
                int len = in.readUnsignedByte();
                yield new String(in.readNBytes(len), StandardCharsets.UTF_8);
            }
            case 4 -> {
                byte[] b = in.readNBytes(16);
                yield InetAddress.getByAddress(b).getHostAddress();
            }
            default -> throw new IOException("未知 ATYP: " + atyp);
        };
        host = EgressHostMatcher.normalizeHost(host);
        if (cmd != 1) {
            reply(out, REP_CMD_NOT_SUPPORTED);
            closeQuietly(client);
            return null;
        }
        return host;
    }

    private void reply(OutputStream out, int rep) throws IOException {
        // VER REP RSV ATYP=1 BND.ADDR=0.0.0.0 BND.PORT=0
        out.write(new byte[]{5, (byte) rep, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** 实际监听端口（socksPort=0  ephemeral 时供测试取真端口） */
    int boundPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    @Override
    public void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 晚于普通 bean 就绪、先于容器关闭收尾
        return Integer.MAX_VALUE;
    }
}

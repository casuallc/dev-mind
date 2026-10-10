package com.devmind.agent.tunnel;

import com.devmind.common.egress.TunnelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * CAP-70 FR-01：服务端侧一条隧道流（SOCKS 本地 socket ↔ 隧道 streamId）。
 * 两个方向各由一条虚拟线程搬运，流控窗口只约束「本地→隧道」发送方向
 * （对端的反向同理在 runner 侧实现）：
 * <ul>
 *   <li>local→tunnel：读 socket 前先在 {@link #sendWindow} 拿 credit（超窗即停读，
 *   TCP 自身缓冲提供背压），收到 WINDOW 帧释放 credit；EOF → 发 CLOSE（半关）；</li>
 *   <li>tunnel→local：DATA 帧入 {@link #inbound} 队列（在途量被对端窗口天然约束），
 *   写线程落 socket 后回 WINDOW credit；收到 CLOSE（毒丸）→ 排空后 shutdownOutput。</li>
 * </ul>
 * RST / 隧道断连 → {@link #abort}：关 socket（两线程随 socket 异常退出）、流表摘除。
 */
public class ServerTunnelStream {

    private static final Logger log = LoggerFactory.getLogger(ServerTunnelStream.class);
    private static final byte[] POISON = new byte[0];

    private final int streamId;
    private final AgentTunnelRegistry.TunnelConn conn;
    private final Socket socket;
    private final Semaphore sendWindow;
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    /** OPEN_ACK waiter：正常收口 = null error；异常收口 = 错误文案 */
    private final CompletableFuture<String> openFuture = new CompletableFuture<>();
    private volatile boolean aborted;

    public ServerTunnelStream(int streamId, AgentTunnelRegistry.TunnelConn conn, Socket socket,
                              int windowBytes) {
        this.streamId = streamId;
        this.conn = conn;
        this.socket = socket;
        this.sendWindow = new Semaphore(windowBytes);
    }

    public int streamId() { return streamId; }

    /** OPEN_ACK waiter：正常收口 = null error；异常收口 = 错误文案 */
    public CompletableFuture<String> openFuture() { return openFuture; }

    /** OPEN_ACK 到达：error == null 成功 */
    void onOpenAck(String error) {
        openFuture.complete(error);
    }

    /** DATA 到达（WS listener 线程）：入队即返回，窗口由对端自控 */
    void onData(byte[] payload) {
        if (!aborted) {
            inbound.offer(payload);
        }
    }

    /** CLOSE 到达：对端发送完毕（半关），排空后 shutdownOutput */
    void onClose() {
        inbound.offer(POISON);
    }

    /** WINDOW 到达：释放发送 credit */
    void onWindow(int bytes) {
        if (bytes > 0) {
            sendWindow.release(bytes);
        }
    }

    /** OPEN_ACK 成功后启动双向搬运（两条虚拟线程） */
    public void start() {
        Thread.ofVirtual().name("tunnel-" + streamId + "-rx").start(this::tunnelToLocal);
        Thread.ofVirtual().name("tunnel-" + streamId + "-tx").start(this::localToTunnel);
    }

    /**
     * 预注入本端→隧道方向的初始字节（HTTP 代理 absolute-form 重写后的请求头块 /
     * CONNECT 头块尾随字节——这些字节已从本地 socket 消费掉，不经 localToTunnel），
     * 占发送窗口与正常 DATA 同口径。须在 {@link #start()} 前调用。
     */
    public void injectOutbound(byte[] bytes) throws IOException, InterruptedException {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        sendWindow.acquire(bytes.length);
        conn.send(TunnelFrame.data(streamId, bytes));
    }

    /** 中止：关 socket + 摘流表（幂等） */
    public void abort(String reason) {
        if (aborted) {
            return;
        }
        aborted = true;
        openFuture.complete(reason == null ? "aborted" : reason);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        conn.streams().remove(streamId);
    }

    private void localToTunnel() {
        byte[] buf = new byte[TunnelFrame.MAX_DATA_BYTES];
        try {
            InputStream in = socket.getInputStream();
            while (!aborted) {
                int n;
                try {
                    sendWindow.acquire(buf.length);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    n = in.read(buf, 0, buf.length);
                } catch (IOException e) {
                    sendWindow.release(buf.length);
                    throw e;
                }
                if (n < 0) {
                    sendWindow.release(buf.length);
                    conn.send(TunnelFrame.close(streamId));
                    return;
                }
                if (n < buf.length) {
                    sendWindow.release(buf.length - n);
                }
                byte[] payload = new byte[n];
                System.arraycopy(buf, 0, payload, 0, n);
                conn.send(TunnelFrame.data(streamId, payload));
            }
        } catch (Exception e) {
            if (!aborted) {
                log.debug("隧道流 {} 本端读取中止: {}", streamId, e.toString());
                conn.sendQuietly(TunnelFrame.rst(streamId, "本端读取失败: " + e));
                abort("local read failed");
            }
        }
    }

    private void tunnelToLocal() {
        try {
            OutputStream out = socket.getOutputStream();
            while (!aborted) {
                byte[] payload;
                try {
                    payload = inbound.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (payload == POISON) {
                    // 对端半关：数据已排空，本端不再向 socket 写
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                out.write(payload);
                out.flush();
                conn.sendQuietly(TunnelFrame.window(streamId, payload.length));
            }
        } catch (Exception e) {
            if (!aborted) {
                log.debug("隧道流 {} 远端数据落盘中止: {}", streamId, e.toString());
                conn.sendQuietly(TunnelFrame.rst(streamId, "本端写入失败: " + e));
                abort("local write failed");
            }
        }
    }

    /** 流收尾探测（Socks5Server 不关 socket，由流自关；供测试断言） */
    boolean isAborted() { return aborted; }
}

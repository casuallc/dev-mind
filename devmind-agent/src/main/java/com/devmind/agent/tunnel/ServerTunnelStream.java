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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
 * 正常收尾（双向 relay 各自 return）与 abort 统一汇入幂等 {@link #terminate}：
 * 摘流表 + 关 socket + 触发一次终结回调（CAP-71 代理审计：字节数/耗时/结果）。
 * 正常路径两条腿都走完才终结（半关语义：本端 EOF 发 CLOSE 后仍要排空对端在途数据）。
 */
public class ServerTunnelStream {

    private static final Logger log = LoggerFactory.getLogger(ServerTunnelStream.class);
    private static final byte[] POISON = new byte[0];

    /** CAP-71 流终结摘要：reason == null 为正常双向收尾，否则为中止原因 */
    public record StreamSummary(int streamId, long bytesUp, long bytesDown, long durationMs,
                                String reason) { }

    private final int streamId;
    private final AgentTunnelRegistry.TunnelConn conn;
    private final Socket socket;
    private final Semaphore sendWindow;
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    /** OPEN_ACK waiter：正常收口 = null error；异常收口 = 错误文案 */
    private final CompletableFuture<String> openFuture = new CompletableFuture<>();
    /** CAP-71 终结回调（审计写入点），openStream 调用方注入；为空不回调 */
    private final Consumer<StreamSummary> onTerminate;
    private final long openedAtMs = System.currentTimeMillis();
    /** local→tunnel 字节数（含 injectOutbound 预注入） */
    private final AtomicLong bytesUp = new AtomicLong();
    /** tunnel→local 字节数 */
    private final AtomicLong bytesDown = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    /** 正常走完的 relay 腿数（到 2 才按正常终结） */
    private final AtomicInteger legsDone = new AtomicInteger(0);
    private volatile boolean aborted;

    public ServerTunnelStream(int streamId, AgentTunnelRegistry.TunnelConn conn, Socket socket,
                              int windowBytes, Consumer<StreamSummary> onTerminate) {
        this.streamId = streamId;
        this.conn = conn;
        this.socket = socket;
        this.sendWindow = new Semaphore(windowBytes);
        this.onTerminate = onTerminate;
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
        bytesUp.addAndGet(bytes.length);
    }

    /** 中止：关 socket + 摘流表（幂等），终结回调 reason 非空 */
    public void abort(String reason) {
        aborted = true;
        terminate(reason == null ? "aborted" : reason);
    }

    /**
     * 统一终结点（幂等）：摘流表 + 关 socket + 触发一次终结回调。
     * abort 与各 relay 腿全部正常 return 后汇入；正常终结 reason = null。
     */
    private void terminate(String reason) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        openFuture.complete(reason);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        conn.streams().remove(streamId);
        if (onTerminate != null) {
            try {
                onTerminate.accept(new StreamSummary(streamId, bytesUp.get(), bytesDown.get(),
                        System.currentTimeMillis() - openedAtMs, reason));
            } catch (Exception e) {
                log.warn("隧道流 {} 终结回调失败: {}", streamId, e.toString());
            }
        }
    }

    /** 一条 relay 腿正常 return：两条腿都走完才按正常终结（半关语义） */
    private void legDone() {
        if (legsDone.incrementAndGet() >= 2) {
            terminate(null);
        }
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
                    legDone();
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
                    legDone();
                    return;
                }
                if (n < buf.length) {
                    sendWindow.release(buf.length - n);
                }
                byte[] payload = new byte[n];
                System.arraycopy(buf, 0, payload, 0, n);
                conn.send(TunnelFrame.data(streamId, payload));
                bytesUp.addAndGet(n);
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
                    legDone();
                    return;
                }
                if (payload == POISON) {
                    // 对端半关：数据已排空，本端不再向 socket 写
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                    legDone();
                    return;
                }
                out.write(payload);
                out.flush();
                bytesDown.addAndGet(payload.length);
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

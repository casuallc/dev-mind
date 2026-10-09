package com.devmind.agent.runner;

import com.devmind.common.egress.TunnelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * CAP-70 FR-01：runner 侧一条隧道流（隧道 streamId ↔ 内网目标 socket）。
 * {@code ServerTunnelStream} 的镜像：两个方向各一条虚拟线程搬运，窗口流控约束
 * 「socket→隧道」发送方向（超窗停读，TCP 缓冲背压），收到 WINDOW 释放 credit；
 * 「隧道→socket」方向写盘成功后回 WINDOW credit。CLOSE 半关、RST/断连中止（幂等）。
 */
public class RunnerTunnelStream {

    private static final Logger log = LoggerFactory.getLogger(RunnerTunnelStream.class);
    private static final byte[] POISON = new byte[0];

    private final int streamId;
    private final Socket socket;
    private final Consumer<TunnelFrame> sender;
    private final IntConsumer onRemove;
    private final Semaphore sendWindow;
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private volatile boolean aborted;

    public RunnerTunnelStream(int streamId, Socket socket, Consumer<TunnelFrame> sender,
                              IntConsumer onRemove, int windowBytes) {
        this.streamId = streamId;
        this.socket = socket;
        this.sender = sender;
        this.onRemove = onRemove;
        this.sendWindow = new Semaphore(windowBytes);
    }

    public int streamId() { return streamId; }

    /** OPEN_ACK 已回后启动双向搬运 */
    public void start() {
        Thread.ofVirtual().name("tunnel-" + streamId + "-rx").start(this::tunnelToSocket);
        Thread.ofVirtual().name("tunnel-" + streamId + "-tx").start(this::socketToTunnel);
    }

    void onData(byte[] payload) {
        if (!aborted) {
            inbound.offer(payload);
        }
    }

    void onClose() {
        inbound.offer(POISON);
    }

    void onWindow(int bytes) {
        if (bytes > 0) {
            sendWindow.release(bytes);
        }
    }

    /** 中止：关 socket + 摘流表（幂等） */
    public void abort(String reason) {
        if (aborted) {
            return;
        }
        aborted = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        onRemove.accept(streamId);
    }

    private void socketToTunnel() {
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
                    sender.accept(TunnelFrame.close(streamId));
                    return;
                }
                if (n < buf.length) {
                    sendWindow.release(buf.length - n);
                }
                byte[] payload = new byte[n];
                System.arraycopy(buf, 0, payload, 0, n);
                sender.accept(TunnelFrame.data(streamId, payload));
            }
        } catch (Exception e) {
            if (!aborted) {
                log.debug("隧道流 {} 目标读取中止: {}", streamId, e.toString());
                sendQuietly(TunnelFrame.rst(streamId, "目标读取失败: " + e));
                abort("remote read failed");
            }
        }
    }

    private void tunnelToSocket() {
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
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                out.write(payload);
                out.flush();
                sendQuietly(TunnelFrame.window(streamId, payload.length));
            }
        } catch (Exception e) {
            if (!aborted) {
                log.debug("隧道流 {} 数据落目标中止: {}", streamId, e.toString());
                sendQuietly(TunnelFrame.rst(streamId, "目标写入失败: " + e));
                abort("remote write failed");
            }
        }
    }

    private void sendQuietly(TunnelFrame frame) {
        try {
            sender.accept(frame);
        } catch (Exception ignored) {
        }
    }
}

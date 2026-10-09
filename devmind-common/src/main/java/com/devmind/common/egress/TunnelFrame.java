package com.devmind.common.egress;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * CAP-70 FR-01：/ws/agent-tunnel 二进制流帧编解码（服务端与 runner 共用，避免双份漂移）。
 *
 * <p>帧布局（首字节为类型，多字节整数大端）：
 * <pre>
 * OPEN(1)      : type, int32 streamId, uint16 hostLen, host(UTF-8), uint16 port
 * OPEN_ACK(2)  : type, int32 streamId, byte ok(0/1), [uint16 errLen, err(UTF-8)]
 * DATA(3)      : type, int32 streamId, payload（≤ {@link #MAX_DATA_BYTES}）
 * CLOSE(4)     : type, int32 streamId        —— 本方向发送完毕（半关）
 * RST(5)       : type, int32 streamId, [uint16 errLen, err(UTF-8)]   —— 双向中止
 * WINDOW(6)    : type, int32 streamId, int32 bytes —— 接收方已消费字节数（流控credit）
 * </pre>
 *
 * <p>流控：每流每方向在途窗口 {@link #DEFAULT_WINDOW_BYTES}，发送方在途超窗即暂停读本地 TCP；
 * 接收方把数据写入本地 TCP 后回 WINDOW credit。隧道握手（tunnel_hello）走文本 JSON 帧，
 * 不在本编解码范围内。</p>
 */
public record TunnelFrame(int type, int streamId, String host, int port, byte[] payload, String error) {

    public static final int TYPE_OPEN = 1;
    public static final int TYPE_OPEN_ACK = 2;
    public static final int TYPE_DATA = 3;
    public static final int TYPE_CLOSE = 4;
    public static final int TYPE_RST = 5;
    public static final int TYPE_WINDOW = 6;

    /** DATA 帧载荷上限（CAP-70 FR-01） */
    public static final int MAX_DATA_BYTES = 32 * 1024;

    /** 每流每方向默认在途窗口（CAP-70 FR-01） */
    public static final int DEFAULT_WINDOW_BYTES = 256 * 1024;

    /** OPEN 帧 host 上限（规范化域名远低于此） */
    private static final int MAX_HOST_BYTES = 512;

    /** RST/OPEN_ACK 错误文案上限 */
    private static final int MAX_ERROR_BYTES = 1024;

    public static TunnelFrame open(int streamId, String host, int port) {
        return new TunnelFrame(TYPE_OPEN, streamId, host, port, null, null);
    }

    public static TunnelFrame openAck(int streamId, String error) {
        return new TunnelFrame(TYPE_OPEN_ACK, streamId, null, 0, null, error);
    }

    public static TunnelFrame data(int streamId, byte[] payload) {
        return new TunnelFrame(TYPE_DATA, streamId, null, 0, payload, null);
    }

    public static TunnelFrame close(int streamId) {
        return new TunnelFrame(TYPE_CLOSE, streamId, null, 0, null, null);
    }

    public static TunnelFrame rst(int streamId, String error) {
        return new TunnelFrame(TYPE_RST, streamId, null, 0, null, error);
    }

    public static TunnelFrame window(int streamId, int bytes) {
        // credit 字节数复用 record 的 port 字段携带（见 windowBytes/encode）
        return new TunnelFrame(TYPE_WINDOW, streamId, null, bytes, null, null);
    }

    public boolean isOk() {
        return type == TYPE_OPEN_ACK && error == null;
    }

    public byte[] encode() {
        return switch (type) {
            case TYPE_OPEN -> {
                byte[] h = host.getBytes(StandardCharsets.UTF_8);
                if (h.length > MAX_HOST_BYTES) {
                    throw new IllegalArgumentException("OPEN host 超长: " + h.length);
                }
                yield ByteBuffer.allocate(1 + 4 + 2 + h.length + 2)
                        .put((byte) type).putInt(streamId).putShort((short) h.length).put(h)
                        .putShort((short) port).array();
            }
            case TYPE_OPEN_ACK -> {
                byte[] e = error == null ? new byte[0] : error.getBytes(StandardCharsets.UTF_8);
                if (e.length > MAX_ERROR_BYTES) {
                    e = Arrays.copyOf(e, MAX_ERROR_BYTES);
                }
                yield ByteBuffer.allocate(1 + 4 + 1 + 2 + e.length)
                        .put((byte) type).putInt(streamId).put((byte) (error == null ? 1 : 0))
                        .putShort((short) e.length).put(e).array();
            }
            case TYPE_RST -> {
                byte[] e = error == null ? new byte[0] : error.getBytes(StandardCharsets.UTF_8);
                if (e.length > MAX_ERROR_BYTES) {
                    e = Arrays.copyOf(e, MAX_ERROR_BYTES);
                }
                yield ByteBuffer.allocate(1 + 4 + 2 + e.length)
                        .put((byte) type).putInt(streamId)
                        .putShort((short) e.length).put(e).array();
            }
            case TYPE_DATA -> {
                if (payload == null || payload.length > MAX_DATA_BYTES) {
                    throw new IllegalArgumentException("DATA 载荷非法: "
                            + (payload == null ? -1 : payload.length));
                }
                yield ByteBuffer.allocate(1 + 4 + payload.length)
                        .put((byte) type).putInt(streamId).put(payload).array();
            }
            case TYPE_CLOSE -> ByteBuffer.allocate(1 + 4).put((byte) type).putInt(streamId).array();
            case TYPE_WINDOW -> ByteBuffer.allocate(1 + 4 + 4).put((byte) type).putInt(streamId)
                    .putInt(port /* 复用 port 字段携带 credit 字节数，见 decode */).array();
            default -> throw new IllegalArgumentException("未知帧类型: " + type);
        };
    }

    /** WINDOW 帧的 credit 字节数（编码时复用 port 字段） */
    public int windowBytes() {
        return port;
    }

    /** 解码失败抛 IllegalArgumentException（调用方视为坏帧，RST 或断开连接） */
    public static TunnelFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < 1) {
            throw new IllegalArgumentException("空帧");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int type = buf.get() & 0xFF;
        switch (type) {
            case TYPE_OPEN -> {
                int streamId = buf.getInt();
                int hostLen = buf.getShort() & 0xFFFF;
                if (hostLen > MAX_HOST_BYTES || buf.remaining() < hostLen + 2) {
                    throw new IllegalArgumentException("OPEN host 非法");
                }
                byte[] h = new byte[hostLen];
                buf.get(h);
                int port = buf.getShort() & 0xFFFF;
                return open(streamId, new String(h, StandardCharsets.UTF_8), port);
            }
            case TYPE_OPEN_ACK -> {
                int streamId = buf.getInt();
                boolean ok = buf.get() != 0;
                String err = readString(buf);
                return new TunnelFrame(TYPE_OPEN_ACK, streamId, null, 0, null, ok ? null : err);
            }
            case TYPE_DATA -> {
                int streamId = buf.getInt();
                if (buf.remaining() > MAX_DATA_BYTES) {
                    throw new IllegalArgumentException("DATA 超上限");
                }
                byte[] p = new byte[buf.remaining()];
                buf.get(p);
                return data(streamId, p);
            }
            case TYPE_CLOSE -> {
                return close(buf.getInt());
            }
            case TYPE_RST -> {
                int streamId = buf.getInt();
                return rst(streamId, readString(buf));
            }
            case TYPE_WINDOW -> {
                int streamId = buf.getInt();
                int credit = buf.getInt();
                return new TunnelFrame(TYPE_WINDOW, streamId, null, credit, null, null);
            }
            default -> throw new IllegalArgumentException("未知帧类型: " + type);
        }
    }

    private static String readString(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return null;
        }
        int len = buf.getShort() & 0xFFFF;
        if (len > MAX_ERROR_BYTES || buf.remaining() < len) {
            throw new IllegalArgumentException("字符串字段非法");
        }
        byte[] b = new byte[len];
        buf.get(b);
        return len == 0 ? null : new String(b, StandardCharsets.UTF_8);
    }
}

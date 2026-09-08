package com.devmind.common.agent;

/**
 * CAP-34 FR-08 runner ↔ 服务端 WS 协议版本常量。hello 帧携带 {@code protocolVersion}，
 * 服务端据此门控下发（老 runner 不认识新帧类型时静默忽略，故门控只用于「必须认识」的帧）。
 *
 * <p>版本史：v1 = CAP-21~FR-03 基线（launch/input/authorize/finish/kill/suspend/hello/heartbeat/
 * event/exit/launched/upgrade/upgrade_ack）；v2 = FR-04~08（对账/GC/版本协商/工具链标签，
 * 均为 hello 可选字段，无新下行帧）。</p>
 */
public final class AgentProtocol {

    /** 当前 runner 协议版本 */
    public static final int CURRENT = 2;

    /** FR-06 exec 帧（构建/部署/测试/发版下发 runner）所需最低版本——本轮未实现，先占位供未来门控 */
    public static final int EXEC_FRAMES = 2;

    /** hello 未携带 protocolVersion 的老 runner 按此版本对待 */
    public static final int DEFAULT_WHEN_ABSENT = 1;

    private AgentProtocol() {
    }
}

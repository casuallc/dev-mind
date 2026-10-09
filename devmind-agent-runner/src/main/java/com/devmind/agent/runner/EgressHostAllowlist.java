package com.devmind.agent.runner;

import com.devmind.common.egress.EgressHostMatcher;

import java.util.List;

/**
 * CAP-70 FR-04：runner 进程级出口白名单 holder（仿 CAP-43 NodeProxy 先例）。
 * 权威源在服务端 DB（egress_rules 本节点 enabled 行的 host_pattern 全集），经隧道握手
 * tunnel_hello 帧全量快照下发、规则变更重推，{@link TunnelConnection} 收到即刷新本 holder。
 *
 * <p>用途是 OPEN 二次校验：服务端已按规则路由，runner 再校验一遍防伪造帧把隧道当成
 * 通用出网代理（CAP-65 双侧校验同哲学）。快照未到达（隧道刚建立）= 空表 = 全部拒绝。</p>
 */
public final class EgressHostAllowlist {

    /** 当前生效的 host glob 快照（已小写规范化；null/空 = 一律拒绝） */
    private static volatile List<String> current = List.of();

    private EgressHostAllowlist() {
    }

    /** tunnel_hello 快照到达即整体替换（pattern 逐个规范化入库，防服务端/中间人塞入怪格式） */
    public static void set(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            current = List.of();
            return;
        }
        current = patterns.stream()
                .map(EgressHostMatcher::normalizePattern)
                .filter(p -> !p.isEmpty())
                .toList();
    }

    /** OPEN 目标是否命中白名单（host 先规范化再逐 pattern 比对，与服务端同语义） */
    public static boolean allows(String host) {
        String h = EgressHostMatcher.normalizeHost(host);
        if (h.isEmpty()) {
            return false;
        }
        for (String pattern : current) {
            if (EgressHostMatcher.matches(pattern, h)) {
                return true;
            }
        }
        return false;
    }

    /** 测试用：清空快照 */
    static void clear() {
        current = List.of();
    }
}

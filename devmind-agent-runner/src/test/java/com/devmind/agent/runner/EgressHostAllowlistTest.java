package com.devmind.agent.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-70 FR-04：runner 侧出口白名单——快照刷新、规范化入库、OPEN 二次校验语义
 * （空快照 = 全拒；与服务端同 matcher 语义）。
 */
class EgressHostAllowlistTest {

    @AfterEach
    void reset() {
        EgressHostAllowlist.clear();
    }

    @Test
    void emptySnapshotDeniesEverything() {
        EgressHostAllowlist.clear();
        assertFalse(EgressHostAllowlist.allows("gitlab.corp.com"));
        EgressHostAllowlist.set(List.of());
        assertFalse(EgressHostAllowlist.allows("gitlab.corp.com"));
    }

    @Test
    void exactAndWildcardMatch() {
        EgressHostAllowlist.set(List.of("gitlab.intra", "*.corp.com"));
        assertTrue(EgressHostAllowlist.allows("gitlab.intra"));
        assertTrue(EgressHostAllowlist.allows("git.corp.com"));
        assertTrue(EgressHostAllowlist.allows("corp.com")); // *.suffix 也匹配裸域（同服务端语义）
        assertFalse(EgressHostAllowlist.allows("corp.com.evil.com"));
        assertFalse(EgressHostAllowlist.allows("other.org"));
    }

    @Test
    void snapshotReplacedWholesaleAndNormalized() {
        EgressHostAllowlist.set(List.of("  *.OLD.com  "));
        assertTrue(EgressHostAllowlist.allows("a.old.com"));
        EgressHostAllowlist.set(List.of("new.intra"));
        assertFalse(EgressHostAllowlist.allows("a.old.com"));
        assertTrue(EgressHostAllowlist.allows("new.intra"));
    }

    @Test
    void hostNormalizedBeforeMatch() {
        EgressHostAllowlist.set(List.of("*.corp.com"));
        assertTrue(EgressHostAllowlist.allows("GIT.Corp.COM."));
        assertTrue(EgressHostAllowlist.allows("git.corp.com:443")); // 端口不敏感
    }
}

package com.devmind.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-34 FR-07 {@link AgentNodeService#labelsCover}：CSV 标签覆盖判定矩阵。 */
class AgentNodeLabelsMatchTest {

    @Test
    void required为空恒匹配() {
        assertTrue(AgentNodeService.labelsCover(null, List.of()));
        assertTrue(AgentNodeService.labelsCover(null, null));
        assertTrue(AgentNodeService.labelsCover("windows", List.of()));
    }

    @Test
    void 全覆盖才匹配() {
        assertTrue(AgentNodeService.labelsCover("windows,office,mvn", List.of("windows", "mvn")));
        assertTrue(AgentNodeService.labelsCover(" windows , office ", List.of("office")));
        assertFalse(AgentNodeService.labelsCover("windows", List.of("windows", "linux")));
        assertFalse(AgentNodeService.labelsCover(null, List.of("mvn")));
        assertFalse(AgentNodeService.labelsCover("", List.of("mvn")));
    }

    @Test
    void 子串不算匹配() {
        assertFalse(AgentNodeService.labelsCover("windows-server", List.of("windows")));
        assertFalse(AgentNodeService.labelsCover("win", List.of("windows")));
    }
}

package com.devmind.agent.service;

import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-65 文件访问根目录白名单：校验（绝对路径/条数/长度）、编解码、归一化匹配。 */
class AgentFileRootsTest {

    @Test
    void validateAcceptsThreeAbsoluteForms() {
        List<String> roots = AgentFileRoots.validate(
                List.of("D:/apusic/data", "d:\\logs", "/var/log", "\\\\nas\\share"));
        assertEquals(4, roots.size());
    }

    @Test
    void validateRejectsRelativePath() {
        var e = assertThrows(DevMindException.class,
                () -> AgentFileRoots.validate(List.of("data/logs")));
        assertTrue(e.getMessage().contains("绝对路径"), e.getMessage());
        // 盘符后无分隔符（"D:foo" 是 Windows 盘相对路径）也拒
        assertThrows(DevMindException.class, () -> AgentFileRoots.validate(List.of("D:foo")));
    }

    @Test
    void validateRejectsOverCountAndOverLength() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            many.add("/r" + i);
        }
        var e = assertThrows(DevMindException.class, () -> AgentFileRoots.validate(many));
        assertTrue(e.getMessage().contains("16"), e.getMessage());
        assertThrows(DevMindException.class,
                () -> AgentFileRoots.validate(List.of("/" + "x".repeat(240))));
    }

    @Test
    void validateTrimsAndDedupes() {
        assertEquals(List.of("/a", "/b"),
                AgentFileRoots.validate(List.of(" /a ", "", "/b", "/a")));
    }

    @Test
    void jsonRoundTripAndClearSemantics() {
        assertNull(AgentFileRoots.toJson(List.of()), "空数组 = 清空（落 null）");
        assertNull(AgentFileRoots.toJson(null));
        String json = AgentFileRoots.toJson(List.of("D:/data", "/var/log"));
        assertEquals(List.of("D:/data", "/var/log"), AgentFileRoots.parse(json));
        // 坏 JSON / 空值按未配置对待
        assertEquals(List.of(), AgentFileRoots.parse("not-json"));
        assertEquals(List.of(), AgentFileRoots.parse(null));
        assertEquals(List.of(), AgentFileRoots.parse("  "));
    }

    @Test
    void normalizeMatchesDriveCaseAndTrailingSeparator() {
        assertEquals(AgentFileRoots.normalize("D:/data"), AgentFileRoots.normalize("d:\\data\\"));
        assertFalse(AgentFileRoots.normalize("D:/data").equals(AgentFileRoots.normalize("D:/data2")));
        assertTrue(AgentFileRoots.containsRoot(List.of("D:/data"), "D:\\data"));
        assertFalse(AgentFileRoots.containsRoot(List.of("D:/data"), "D:/data/sub"));
    }
}

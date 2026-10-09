package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-68：会话文件附件落盘文件名净化——basename 防穿越、去控制字符与 ".."，
 * 保留中文等 Unicode 字符（不能走 ContextMaterializer 的 ASCII 白名单）。
 */
class AgentRunnerInputFileNameTest {

    @Test
    void keepsChineseAndCommonChars() {
        assertEquals("设计稿.png", AgentRunnerMain.sanitizeIncomingName("设计稿.png"));
        assertEquals("需求说明书 v2.pdf", AgentRunnerMain.sanitizeIncomingName("需求说明书 v2.pdf"));
    }

    @Test
    void stripsPathTraversal() {
        assertEquals("passwd", AgentRunnerMain.sanitizeIncomingName("../../etc/passwd"));
        assertEquals("evil.txt", AgentRunnerMain.sanitizeIncomingName("C:\\temp\\evil.txt"));
        assertEquals("a.txt", AgentRunnerMain.sanitizeIncomingName("dir/sub/a.txt"));
        assertEquals("a_b.txt", AgentRunnerMain.sanitizeIncomingName("a..b.txt"));
    }

    @Test
    void blankAndDotFallback() {
        assertEquals("file", AgentRunnerMain.sanitizeIncomingName(""));
        assertEquals("file", AgentRunnerMain.sanitizeIncomingName(null));
        assertEquals("file", AgentRunnerMain.sanitizeIncomingName("."));
        assertEquals("file", AgentRunnerMain.sanitizeIncomingName("  "));
    }

    @Test
    void controlCharsReplaced() {
        assertEquals("a_b.txt", AgentRunnerMain.sanitizeIncomingName("a\nb.txt"));
    }
}

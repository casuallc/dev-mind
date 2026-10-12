package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OutputUploader#isValidOutputName}：产出文件名白名单（与服务端 AgentOutputController 对齐）。 */
class OutputUploaderNameTest {

    @Test
    void acceptsAsciiAndCjkNames() {
        assertTrue(OutputUploader.isValidOutputName("analysis.md"));
        assertTrue(OutputUploader.isValidOutputName("wi-plan.json"));
        assertTrue(OutputUploader.isValidOutputName("20261012-复扫报告处理状态.md"));
        assertTrue(OutputUploader.isValidOutputName("版本对照表.md"));
        assertTrue(OutputUploader.isValidOutputName("最终残留漏洞清单.md"));
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        assertFalse(OutputUploader.isValidOutputName("../evil.md"));
        assertFalse(OutputUploader.isValidOutputName("a/b.md"));
        assertFalse(OutputUploader.isValidOutputName("a\\b.md"));
        assertFalse(OutputUploader.isValidOutputName(null));
        assertFalse(OutputUploader.isValidOutputName(""));
    }

    @Test
    void rejectsOverlongAndControlChars() {
        assertFalse(OutputUploader.isValidOutputName("x".repeat(65)));
        assertFalse(OutputUploader.isValidOutputName("a b.md"));
        assertFalse(OutputUploader.isValidOutputName("a\tb.md"));
    }
}

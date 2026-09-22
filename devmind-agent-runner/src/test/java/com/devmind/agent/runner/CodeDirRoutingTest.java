package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CodeDirRouting}：cwd 级恒定路由文件 + 首条消息【代码目录】前缀。 */
class CodeDirRoutingTest {

    @TempDir
    Path tmp;

    @Test
    void routingFileIsConstantAndIdempotent() throws Exception {
        CodeDirRouting.writeRoutingFile(tmp);
        String first = Files.readString(tmp.resolve(CodeDirRouting.ROUTING_FILE), StandardCharsets.UTF_8);
        CodeDirRouting.writeRoutingFile(tmp);
        assertEquals(first, Files.readString(tmp.resolve(CodeDirRouting.ROUTING_FILE),
                StandardCharsets.UTF_8), "并发会话共享 cwd：内容必须恒定（幂等覆盖）");
        assertTrue(first.contains("不是代码目录"), first);
        assertTrue(first.contains("main/"), "必须声明克隆缓存禁入");
    }

    @Test
    void prefixPointsToCodeDirAndKeepsTaskSpec() {
        String spec = CodeDirRouting.prefixTaskSpec("[flow:dev]\n做需求", "worktrees/req-r1");
        assertTrue(spec.startsWith("【代码目录】worktrees/req-r1/"), spec);
        assertTrue(spec.contains("CLAUDE.local.md"), "必须引导先读代码目录下的会话注入块");
        assertTrue(spec.endsWith("[flow:dev]\n做需求"), "原 taskSpec 原样保留在后");
        // 空 taskSpec → 前缀自身即完整引导
        assertTrue(CodeDirRouting.prefixTaskSpec("", "work").startsWith("【代码目录】work/"));
    }

    @Test
    void relativizeUsesForwardSlashes() {
        Path cwd = tmp.resolve("proj/admin");
        Path code = cwd.resolve("worktrees/req-r1");
        assertEquals("worktrees/req-r1", CodeDirRouting.relativize(cwd, code));
    }
}

package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-59 Tab 补全解析纯函数面：首词位补命令、cd 只补目录、其余补文件、
 * 行尾空白 = 新词位、管道/分号段取最后一段。
 */
class TerminalHandlerCompleteParseTest {

    @Test
    void blankInputCompletesCommand() {
        var p = TerminalHandler.parseCompletion("");
        assertEquals("", p.word());
        assertEquals("c", p.mode());
    }

    @Test
    void secondTokenIsArgumentNotCommand() {
        // "git sta" 补的是 git 的参数 → 文件模式；只有单 token 才算首词位
        var p = TerminalHandler.parseCompletion("git sta");
        assertEquals("sta", p.word());
        assertEquals("f", p.mode());
    }

    @Test
    void singleTokenIsFirstWord() {
        var p = TerminalHandler.parseCompletion("gi");
        assertEquals("gi", p.word());
        assertEquals("c", p.mode());
    }

    @Test
    void argumentCompletesFile() {
        var p = TerminalHandler.parseCompletion("cat pom.x");
        assertEquals("pom.x", p.word());
        assertEquals("f", p.mode());
    }

    @Test
    void cdCompletesDirectoryOnly() {
        var p = TerminalHandler.parseCompletion("cd fro");
        assertEquals("fro", p.word());
        assertEquals("d", p.mode());
    }

    @Test
    void trailingSpaceMeansNewWord() {
        var p = TerminalHandler.parseCompletion("cat ");
        assertEquals("", p.word());
        assertEquals("f", p.mode());
        var cd = TerminalHandler.parseCompletion("cd ");
        assertEquals("", cd.word());
        assertEquals("d", cd.mode());
    }

    @Test
    void pipeSegmentIsTheCurrentCommand() {
        var p = TerminalHandler.parseCompletion("cat pom.xml | gre");
        assertEquals("gre", p.word());
        assertEquals("c", p.mode()); // 段首词位 → 补命令
        var f = TerminalHandler.parseCompletion("ls | grep fro");
        assertEquals("fro", f.word());
        assertEquals("f", f.mode()); // grep 的参数位 → 补文件
        var arg = TerminalHandler.parseCompletion("git log | head pom");
        assertEquals("pom", arg.word());
        assertEquals("f", arg.mode());
    }
}

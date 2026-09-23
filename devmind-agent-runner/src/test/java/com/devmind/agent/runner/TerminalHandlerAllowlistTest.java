package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-58 terminalAllowlist 纯函数面：缺省只读档（configured=null）；显式配置全覆盖
 * （空表=拒一切）；按 |/&&/;/换行 切段逐段校验；git 子命令只读门（git:* 全放行）；
 * 重定向写入默认拒绝（allowRedirect 放开）。
 */
class TerminalHandlerAllowlistTest {

    @Test
    void defaultReadonlySetWhenAbsent() {
        // configured=null → 内置只读档
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "ls -la"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "cat pom.xml | head -5"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "cd frontend && ls"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "git status"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "rm -rf build"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "sed -n 1p pom.xml"));
    }

    @Test
    void explicitConfigOverridesDefault() {
        // 显式空表 = 拒绝一切；显式名单 = 全覆盖（默认档里的 ls 也不再天然可用）
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(List.of(), false, "ls"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(List.of("npm"), false, "npm run build"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(List.of("npm"), false, "ls"));
        assertTrue(e.getMessage().contains("terminalAllowlist"), e.getMessage());
    }

    @Test
    void segmentsCheckedIndividually() {
        // 管道/逻辑与或/分号任一段越名单即拒
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false,
                "git log --oneline -3 && echo done"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "ls | xargs rm"));
        assertTrue(e.getMessage().contains("xargs"), e.getMessage());
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "pwd; curl http://evil"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false,
                "if [ -f pom.xml ]; then cat pom.xml; fi"));
    }

    @Test
    void gitSubcommandGate() {
        // 只读子命令放行（含全局选项前置）
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "git diff HEAD~1"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "git -C subdir log --oneline"));
        // 写子命令默认拒绝
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "git commit -m x"));
        assertTrue(e.getMessage().contains("git commit"), e.getMessage());
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "git push"));
        // git:* 放行全部子命令
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(List.of("git:*"), false,
                "git commit -m x"));
    }

    @Test
    void redirectRejectedByDefault() {
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "echo hi > a.txt"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "git log >> log.txt"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(null, false, "ls &> out.txt"));
        // 2>&1 流合并不是文件写入，放行
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, false, "git status 2>&1"));
        // 配置放开
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(null, true, "echo hi > a.txt"));
    }
}

package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M2 runner 侧终端写档与硬阻断纯函数面：
 * <ul>
 *   <li>写档（DEFAULT_WRITE_ALLOWLIST）放行 git 全子命令/写类命令/构建命令 + 重定向；</li>
 *   <li>硬阻断（checkHardBlocks）与白名单正交：rm 递归删根/HOME、fork 炸弹、mkfs、
 *       info/exclude 改写——即使管理员把 rm 配进白名单也放不过；</li>
 *   <li>硬阻断两档通吃（只读档也先过 checkHardBlocks）。</li>
 * </ul>
 */
class TerminalHandlerHardBlockTest {

    // ---------------- 写档白名单 ----------------

    @Test
    void writeProfileAllowsGitWriteAndRedirects() {
        List<String> w = TerminalHandler.DEFAULT_WRITE_ALLOWLIST;
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "git add ."));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "git commit -m x"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "git push origin terminal/abc"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "git checkout -b feature/x"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "echo hi > a.txt"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "echo hi >> a.txt"));
    }

    @Test
    void writeProfileAllowsFileOpsAndBuilds() {
        List<String> w = TerminalHandler.DEFAULT_WRITE_ALLOWLIST;
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "cp a.txt b.txt"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "mv a.txt sub/b.txt"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "mkdir -p sub/dir"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "touch new.txt"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "sed -i s/a/b/ a.txt"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "mvn -q test"));
        assertDoesNotThrow(() -> TerminalHandler.checkAllowlist(w, true, "npm run build"));
        // rm 仍不在写档（删除走危险命令确认通道——但 runner 档根本没有 rm，服务端 428 后
        // confirmed 放行也会被 runner 白名单拒：双层防线口径，rm 一律不可用）
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkAllowlist(w, true, "rm -rf build"));
    }

    // ---------------- 硬阻断（与白名单正交） ----------------

    @Test
    void hardBlockRmRecursiveRootAndHome() {
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf /"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf /*"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf ~"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf $HOME"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf ${HOME}"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm -rf ~/"));
        assertTrue(e.getMessage().contains("硬阻断"), e.getMessage());
    }

    @Test
    void hardBlockSparesOrdinaryRmAndNonRecursive() {
        // 普通路径的 rm -rf 不硬阻断（归服务端 428 灰区 + runner 白名单档管）
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks("rm -rf build"));
        // 非递归删 HOME 文件不是「递归毁灭」特征（仍受白名单管）
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks("rm -f ~/.bashrc"));
    }

    @Test
    void hardBlockForkBombAndMkfs() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks(":(){ :|:& };:"));
        assertTrue(e.getMessage().contains("fork"), e.getMessage());
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("mkfs /dev/sda1"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("mkfs.ntfs /dev/sda1"));
    }

    @Test
    void hardBlockInfoExcludeWrites() {
        // CAP-42 FR-10：平台物化配置路径禁终端改写（读不受影响）
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("echo x >> .git/info/exclude"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("rm .git/info/exclude"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("sed -i s/a/b/ .git/info/exclude"));
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("cp x .git/info/exclude"));
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks("cat .git/info/exclude"));
    }

    @Test
    void hardBlockAppliesPerSegment() {
        assertThrows(IllegalStateException.class,
                () -> TerminalHandler.checkHardBlocks("git status && rm -rf /"));
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks("git status && ls"));
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks(null));
        assertDoesNotThrow(() -> TerminalHandler.checkHardBlocks("   "));
    }
}

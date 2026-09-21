package com.devmind.project;

import com.devmind.project.config.WorktreeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-51 FR-01/FR-02：需求粒度工作区键与分支的服务端唯一命名口径。
 * 键随 launch/finalize/release 帧下发（runner 不推导），分支写进 session_repos 快照——
 * 两者必须与收口/释放/diff 的现算值逐字节一致，否则 runner 的 {@code ensureUserWorktree}
 * 会判「检出分支与会话分支不一致」。
 */
class WorktreeManagerWorkspaceNamingTest {

    private final WorktreeManager manager = new WorktreeManager(new WorktreeProperties(), null);

    @Test
    void 需求锚定会话用需求键与需求分支() {
        assertEquals("req-abc12345", manager.workspaceKeyFor("abc12345", "s1a2b3c4"));
        assertEquals("feature/req-abc12345", manager.branchFor("abc12345", "s1a2b3c4"));
    }

    @Test
    void 无需求会话沿用会话键与会话分支() {
        assertEquals("sid-s1a2b3c4", manager.workspaceKeyFor(null, "s1a2b3c4"));
        assertEquals("feature/s1a2b3c4", manager.branchFor(null, "s1a2b3c4"));
        assertEquals("sid-s1a2b3c4", manager.workspaceKeyFor("  ", "s1a2b3c4"));
        assertEquals("feature/s1a2b3c4", manager.branchFor("  ", "s1a2b3c4"));
    }

    /** 旧签名（无需求维度）行为不变：存量调用点按 sid 语义走，不破。 */
    @Test
    void 旧签名行为不变() {
        assertEquals("feature/s1a2b3c4", manager.branchFor("s1a2b3c4"));
    }

    /** 键必须落在 runner 侧 SAFE_ID 白名单（[a-zA-Z0-9._-]），否则 launch 必失败。 */
    @Test
    void 键落在runner安全字符白名单内() {
        String key = manager.workspaceKeyFor("abc12345", "s1a2b3c4");
        assertEquals(true, key.matches("[a-zA-Z0-9._-]+"), "键含 runner 不接受的字符: " + key);
    }
}

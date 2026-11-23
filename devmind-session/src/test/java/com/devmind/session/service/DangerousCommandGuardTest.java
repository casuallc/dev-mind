package com.devmind.session.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M2 服务端危险命令护栏纯函数面：rm -rf / git push -f / git reset --hard /
 * git clean -fd / mkfs / fork 炸弹命中；常规只读与非强制 git 写操作放行；
 * 管道/分号切段逐段判定；git 全局选项前置不误判。
 */
class DangerousCommandGuardTest {

    @Test
    void safeCommandsPass() {
        assertNull(DangerousCommandGuard.check(null));
        assertNull(DangerousCommandGuard.check("  "));
        assertNull(DangerousCommandGuard.check("ls -la"));
        assertNull(DangerousCommandGuard.check("git status && git log --oneline -3"));
        assertNull(DangerousCommandGuard.check("git add . && git commit -m 'x'"));
        assertNull(DangerousCommandGuard.check("git push origin terminal/abc12345"));
        assertNull(DangerousCommandGuard.check("rm -f single.txt"));          // 非递归
        assertNull(DangerousCommandGuard.check("rm -r build"));               // 非强制
        assertNull(DangerousCommandGuard.check("git clean -n"));              // 演练不删
        assertNull(DangerousCommandGuard.check("git reset --soft HEAD~1"));   // 非 hard
        assertNull(DangerousCommandGuard.check("mvn -q test"));
    }

    @Test
    void rmRecursiveForceFlagged() {
        String why = DangerousCommandGuard.check("rm -rf build/");
        assertNotNull(why);
        assertTrue(why.contains("rm -rf"), why);
        assertNotNull(DangerousCommandGuard.check("rm -fr /tmp/x"));
        assertNotNull(DangerousCommandGuard.check("rm -f -r .git"));
    }

    @Test
    void gitForcePushFlagged() {
        assertNotNull(DangerousCommandGuard.check("git push --force"));
        assertNotNull(DangerousCommandGuard.check("git push -f origin main"));
        assertNotNull(DangerousCommandGuard.check("git push --force-with-lease"));
        // 全局选项前置不影响子命令识别
        assertNotNull(DangerousCommandGuard.check("git -C repo push -f"));
        // push 之外子命令带 -f（如 git branch -f）不在本规则集
        assertNull(DangerousCommandGuard.check("git branch -f x y"));
    }

    @Test
    void gitResetHardAndCleanFdFlagged() {
        assertNotNull(DangerousCommandGuard.check("git reset --hard HEAD"));
        assertNotNull(DangerousCommandGuard.check("git clean -fd"));
        assertNotNull(DangerousCommandGuard.check("git clean -dfx"));
        assertNull(DangerousCommandGuard.check("git clean -f"));   // 只文件不递归目录
    }

    @Test
    void mkfsAndForkBombFlagged() {
        assertNotNull(DangerousCommandGuard.check("mkfs /dev/sda1"));
        assertNotNull(DangerousCommandGuard.check("mkfs.ext4 /dev/sda1"));
        assertNotNull(DangerousCommandGuard.check(":(){ :|:& };:"));
    }

    @Test
    void segmentsCheckedIndividually() {
        assertNotNull(DangerousCommandGuard.check("ls && rm -rf build"));
        assertNotNull(DangerousCommandGuard.check("echo ok; git reset --hard"));
        assertNotNull(DangerousCommandGuard.check("true || git clean -fd"));
        assertNull(DangerousCommandGuard.check("echo 'rm -rf /'")); // 首 token 是 echo 不命中
        // 说明：护栏词法不解析引号——"echo 'a && rm -rf b'" 这类引号内含分隔符的写法会误切段
        // 命中 428；前端确认放行即可，灰区误报可接受（宁严勿宽）。
    }
}

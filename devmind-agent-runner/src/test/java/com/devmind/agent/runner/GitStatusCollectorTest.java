package com.devmind.agent.runner;

import com.devmind.common.util.GitCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-54 GitStatusCollector 纯函数面：porcelain 解析（重命名取新路径、引号剥离、短行跳过）、
 * numstat 合并（staged+unstaged 累加、二进制行跳过）、仓库发现（代码目录本身是库 → 单条；
 * 聚合根 → 各带 .git 的直接子目录；worktree 的 .git 是文件也要认）。
 */
class GitStatusCollectorTest {

    @Test
    void parsePorcelain基本状态与重命名() {
        String out = """
                M  staged.txt
                 M unstaged.txt
                ?? new file.txt
                R  old.txt -> new.txt
                D  gone.txt
                """;
        Map<String, String> codes = GitStatusCollector.parsePorcelain(out);
        assertEquals("M ", codes.get("staged.txt"));
        assertEquals(" M", codes.get("unstaged.txt"));
        assertEquals("??", codes.get("new file.txt"));
        assertEquals("new.txt", codes.containsKey("new.txt") ? "new.txt" : null);
        assertEquals("R ", codes.get("new.txt"));
        assertFalse(codes.containsKey("old.txt"), "重命名取新路径");
        assertEquals("D ", codes.get("gone.txt"));
    }

    @Test
    void parsePorcelain剥离引号并跳过短行() {
        String out = "\"\\344\\270\\255\\346\\226\\207.txt\"\n?? \" spaced .txt \"\n\nM\n";
        Map<String, String> codes = GitStatusCollector.parsePorcelain(out);
        // 引号剥离：porcelain 对特殊路径整体加引号，展示用剥掉首尾即可
        assertTrue(codes.containsKey(" spaced .txt "), codes.keySet().toString());
        assertFalse(codes.containsValue(null));
        assertFalse(codes.containsKey(""), "空路径不入");
    }

    @Test
    void mergeNumstat累加两段并跳过二进制() {
        Map<String, int[]> acc = new HashMap<>();
        GitStatusCollector.mergeNumstat(acc, new GitCli.Result(0, "10\t2\ta.txt\n3\t0\tb.txt\n", ""));
        GitStatusCollector.mergeNumstat(acc, new GitCli.Result(0, "1\t1\ta.txt\n-\t-\tbin.png\n", ""));
        assertEquals(11, acc.get("a.txt")[0]);
        assertEquals(3, acc.get("a.txt")[1]);
        assertEquals(3, acc.get("b.txt")[0]);
        assertFalse(acc.containsKey("bin.png"), "二进制 numstat 行跳过");
        // 非零退出（如未暂存任何东西时的边缘情况）不影响已有累计
        GitStatusCollector.mergeNumstat(acc, new GitCli.Result(1, "", "err"));
        assertEquals(11, acc.get("a.txt")[0]);
    }

    @Test
    void findRepos代码目录本身是库(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".git"), "gitdir: /elsewhere"); // worktree 指针文件也认
        List<Path> repos = GitStatusCollector.findRepos(dir);
        assertEquals(1, repos.size());
        assertEquals(dir, repos.get(0));
    }

    @Test
    void findRepos聚合根扫直接子目录(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectory(dir.resolve("alpha"));
        Path b = Files.createDirectory(dir.resolve("beta"));
        Files.createDirectory(dir.resolve("not-a-repo"));
        Files.createDirectories(a.resolve(".git"));
        Files.writeString(b.resolve(".git"), "gitdir: x");
        List<Path> repos = GitStatusCollector.findRepos(dir);
        assertEquals(2, repos.size());
        assertTrue(repos.contains(a) && repos.contains(b));
    }

    @Test
    void findRepos空目录与非目录(@TempDir Path dir) {
        assertTrue(GitStatusCollector.findRepos(dir).isEmpty());
        assertTrue(GitStatusCollector.findRepos(dir.resolve("missing")).isEmpty());
        assertTrue(GitStatusCollector.findRepos(null).isEmpty());
    }
}

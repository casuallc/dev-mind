package com.devmind.worklog.service;

import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.common.util.GitCli;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.dto.GitScanRepoDiag;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scanDetailed 端到端（真实 git 仓库）：诊断产出 + author 过滤链的本地 user.email 回退。
 * 无 mockito 依赖：CodeRepoService 匿名子类覆写、WorklogEntryRepository 用 JDK 代理。
 */
class GitLogScannerScanTest {

    @TempDir
    Path repoDir;

    private GitLogScanner scannerWith(List<GitRepoCatalog.RepoRef> repos) {
        return scannerWithSelections(repos.stream()
                .map(r -> new CodeRepoService.SubscribedRepo(r, List.of()))
                .toList());
    }

    private GitLogScanner scannerWithSelections(List<CodeRepoService.SubscribedRepo> subs) {
        CodeRepoService codeRepoService = new CodeRepoService(null, null, null) {
            @Override
            public List<CodeRepoService.SubscribedRepo> subscribedRepos(String username) {
                return subs;
            }
        };
        WorklogEntryRepository entryRepo = (WorklogEntryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{WorklogEntryRepository.class},
                (proxy, method, args) -> {
                    if ("existsByUserIdAndRepoIdAndCommitSha".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        // integration 缺席场景：无 GitIdentityProvider，走仓库本地 user.email 回退
        ObjectProvider<GitIdentityProvider> noProvider = new ObjectProvider<>() {
            @Override
            public GitIdentityProvider getObject() {
                return null;
            }

            @Override
            public GitIdentityProvider getIfAvailable() {
                return null;
            }
        };
        return new GitLogScanner(codeRepoService, entryRepo, new WorklogProperties(), noProvider);
    }

    private void git(String... args) {
        GitCli.Result r = GitCli.run(repoDir, 15, args);
        assertEquals(0, r.exitCode(), () -> String.join(" ", args) + " -> " + r.err());
    }

    private Path initRepoWithCommit() throws Exception {
        git("git", "init");
        git("git", "config", "user.name", "测试人");
        git("git", "config", "user.email", "me@example.com");
        Files.writeString(repoDir.resolve("a.txt"), "hello");
        git("git", "add", "a.txt");
        git("git", "commit", "-m", "当日提交");
        return repoDir;
    }

    @Test
    void scansLocalRepoWithLocalEmailFallback() throws Exception {
        Path repo = initRepoWithCommit();
        GitLogScanner scanner = scannerWith(List.of(
                new GitRepoCatalog.RepoRef(1, "demo", repo.toString(), null, null, "ACTIVE", "NONE", List.of())));

        GitPreviewResponse res = scanner.scanDetailed("u1", LocalDate.now());

        assertEquals(1, res.commits().size());
        assertEquals("当日提交", res.commits().get(0).subject());
        assertEquals(1, res.repos().size());
        GitScanRepoDiag diag = res.repos().get(0);
        assertEquals("SCANNED", diag.outcome());
        // 无个人凭证时回退到仓库本地 git config user.email
        assertEquals("me@example.com", diag.authorFilter());
        assertEquals(1, diag.commitCount());
    }

    @Test
    void scansDateRangeInclusive() throws Exception {
        Path repo = initRepoWithCommit();
        GitLogScanner scanner = scannerWith(List.of(
                new GitRepoCatalog.RepoRef(1, "demo", repo.toString(), null, null, "ACTIVE", "NONE", List.of())));

        // 含今天的范围能扫到当日提交
        GitPreviewResponse hit = scanner.scanDetailed("u1", LocalDate.now().minusDays(3), LocalDate.now());
        assertEquals(1, hit.commits().size());
        assertEquals("范围内没有署名「me@example.com」的提交",
                scanner.scanDetailed("u1", LocalDate.now().minusDays(5), LocalDate.now().minusDays(3))
                        .repos().get(0).detail());
    }

    @Test
    void skipsCloningAndDisabledReposWithReason() {
        GitLogScanner scanner = scannerWith(List.of(
                new GitRepoCatalog.RepoRef(1, "cloning", repoDir.toString(), null, null, "ACTIVE", "CLONING", List.of()),
                new GitRepoCatalog.RepoRef(2, "disabled", repoDir.toString(), null, null, "DISABLED", "NONE", List.of())));

        GitPreviewResponse res = scanner.scanDetailed("u1", LocalDate.now());

        assertTrue(res.commits().isEmpty());
        assertEquals(2, res.repos().size());
        assertEquals("SKIPPED", res.repos().get(0).outcome());
        assertNotNull(res.repos().get(0).detail());
        assertEquals("SKIPPED", res.repos().get(1).outcome());
    }

    @Test
    void missingPathSurfacedAsFailed() {
        GitLogScanner scanner = scannerWith(List.of(
                new GitRepoCatalog.RepoRef(1, "gone", repoDir.resolve("not-exist").toString(),
                        null, null, "ACTIVE", "NONE", List.of())));

        GitPreviewResponse res = scanner.scanDetailed("u1", LocalDate.now());

        assertTrue(res.commits().isEmpty());
        assertEquals(1, res.repos().size());
        assertEquals("FAILED", res.repos().get(0).outcome());
    }

    @Test
    void scansSelectedBranchesWithShaDedupe() throws Exception {
        Path repo = initRepoWithCommit();
        git("git", "checkout", "-b", "feature");
        Files.writeString(repoDir.resolve("b.txt"), "branch work");
        git("git", "add", "b.txt");
        git("git", "commit", "-m", "特性分支提交");
        GitCli.Result cur = GitCli.run(repoDir, 15, "git", "branch", "--show-current");
        assertEquals("feature", cur.out().strip());

        GitRepoCatalog.RepoRef ref = new GitRepoCatalog.RepoRef(
                1, "demo", repo.toString(), null, null, "ACTIVE", "NONE", List.of());
        // 勾选 master/main 主干 + feature：主干提交是 feature 的祖先，应按 sha 去重只出一次
        String trunk = trunkBranch();
        GitLogScanner scanner = scannerWithSelections(List.of(
                new CodeRepoService.SubscribedRepo(ref, List.of(trunk, "feature"))));

        GitPreviewResponse res = scanner.scanDetailed("u1", LocalDate.now());

        assertEquals(2, res.commits().size());
        assertTrue(res.commits().stream().anyMatch(c -> "当日提交".equals(c.subject())));
        assertTrue(res.commits().stream().anyMatch(c -> "特性分支提交".equals(c.subject())));
        assertEquals("SCANNED", res.repos().get(0).outcome());
        assertEquals(2, res.repos().get(0).commitCount());
    }

    @Test
    void missingSelectedBranchNotedAndSkipped() throws Exception {
        Path repo = initRepoWithCommit();
        GitRepoCatalog.RepoRef ref = new GitRepoCatalog.RepoRef(
                1, "demo", repo.toString(), null, null, "ACTIVE", "NONE", List.of());
        GitLogScanner scanner = scannerWithSelections(List.of(
                new CodeRepoService.SubscribedRepo(ref, List.of(trunkBranch(), "no-such-branch"))));

        GitPreviewResponse res = scanner.scanDetailed("u1", LocalDate.now());

        assertEquals(1, res.commits().size());
        GitScanRepoDiag diag = res.repos().get(0);
        assertEquals("SCANNED", diag.outcome());
        assertTrue(diag.detail().contains("no-such-branch"), diag.detail());
        assertTrue(diag.detail().contains("不存在"), diag.detail());
    }

    /** 仓库主干分支名（git init 默认分支随版本为 master 或 main）。 */
    private String trunkBranch() {
        GitCli.Result r = GitCli.run(repoDir, 15, "git", "rev-parse", "--verify", "--quiet", "refs/heads/master");
        return r.exitCode() == 0 ? "master" : "main";
    }
}

package com.devmind.knowledge.sync;

import com.devmind.common.exception.DevMindException;
import com.devmind.knowledge.KnowledgeVersionService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.task.api.TaskContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-85 FR-27 Git 目录同步：真实临时仓库跑通全量→增量→删除→失效补扫。
 * 无 git 可执行文件的环境自动跳过（CI 容器可能不装）。
 */
class GitDocSyncTest {

    @TempDir
    Path repoDir;

    private final Map<Long, KnowledgeEntryEntity> byId = new HashMap<>();
    private final Map<String, KnowledgeEntryEntity> byExternal = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private KnowledgeEntryRepository entryRepo;
    private KnowledgeSyncSourceEntity source;
    private GitDocSync sync;
    private TaskContext ctx;

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(3L);
        kb.setName("研发文档库");
        kb.setScope(KnowledgeBaseEntity.SCOPE_GLOBAL);
        when(kbRepo.findById(3L)).thenReturn(Optional.of(kb));

        when(entryRepo.save(any())).thenAnswer(inv -> {
            KnowledgeEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.incrementAndGet());
            }
            byId.put(e.getId(), e);
            if (e.getExternalId() != null) {
                byExternal.put(e.getKbId() + ":" + e.getExternalId(), e);
            }
            return e;
        });
        when(entryRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        when(entryRepo.findByKbIdAndExternalId(anyLong(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(byExternal.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(entryRepo.findRefsBySyncSourceId(anyLong())).thenAnswer(inv -> byId.values().stream()
                .filter(e -> Objects.equals(inv.getArgument(0), e.getSyncSourceId()))
                .map(e -> new SyncRef(e.getId(), e.getExternalId()))
                .toList());

        SyncedEntryWriter writer = new SyncedEntryWriter(entryRepo,
                mock(KnowledgeVersionService.class), mock(ApplicationEventPublisher.class));
        sync = new GitDocSync(kbRepo, entryRepo, writer,
                new com.devmind.knowledge.config.KnowledgeProperties(), new ObjectMapper());

        source = new KnowledgeSyncSourceEntity();
        source.setId(7L);
        source.setKbId(3L);
        source.setKind(KnowledgeSyncSourceEntity.KIND_GIT);
        source.setConfigJson("{\"repoPath\":\"" + jsonPath(repoDir)
                + "\",\"ref\":\"HEAD\",\"docsPath\":\"docs\",\"tags\":[\"git\"]}");

        ctx = mock(TaskContext.class);
        when(ctx.isCancelled()).thenReturn(false);
    }

    @Test
    void fullThenIncrementalThenDeletion() throws Exception {
        assumeTrue(gitAvailable(), "环境无 git 可执行文件，跳过");
        git("init", "-q");
        write("docs/deploy.md", "# 部署手册\n\n部署前核对连接串与端口白名单。\n");
        write("docs/faq.md", "# 常见问题\n\n服务启动失败时先看日志末尾。\n");
        write("docs/note.txt", "非 Markdown 不入库");
        commit("init");
        String firstSha = git("rev-parse", "HEAD").trim();

        SyncOutcome first = sync.run(source, ctx);

        assertEquals(2, first.created(), "只同步 docs 下的 Markdown");
        assertEquals(0, first.failed());
        assertEquals(firstSha, first.watermark());
        assertEquals(2, byId.size());
        KnowledgeEntryEntity deploy = byExternal.get("3:sync:7:docs/deploy.md");
        assertNotNull(deploy);
        assertEquals("部署手册", deploy.getName(), "标题取正文一级标题");
        assertEquals(KnowledgeEntryEntity.SOURCE_GIT, deploy.getSource());
        assertEquals(7L, deploy.getSyncSourceId());
        assertTrue(deploy.getTags().contains("git"));
        source.setWatermark(first.watermark());

        SyncOutcome second = sync.run(source, ctx);
        assertEquals(0, second.created());
        assertEquals(0, second.updated(), "同一 commit 无 diff");
        source.setWatermark(second.watermark());

        write("docs/deploy.md", "# 部署手册\n\n新增：灰度发布前必须备份配置。\n");
        commit("update deploy");
        String secondSha = git("rev-parse", "HEAD").trim();
        SyncOutcome third = sync.run(source, ctx);

        assertEquals(1, third.updated(), "按 watermark..HEAD 增量只处理变更文件");
        assertEquals(secondSha, third.watermark());
        assertTrue(deploy.getContentMd().contains("灰度发布"), deploy.getContentMd());
        assertNotEquals(firstSha, secondSha);
        source.setWatermark(third.watermark());

        Files.delete(repoDir.resolve("docs/faq.md"));
        commit("delete faq");
        SyncOutcome fourth = sync.run(source, ctx);

        assertEquals(1, fourth.deprecated(), "源端删除只标记 deprecated");
        KnowledgeEntryEntity faq = byExternal.get("3:sync:7:docs/faq.md");
        assertEquals("deprecated", faq.getStatus());
        assertNotNull(faq.getValidTo());
        assertEquals("active", deploy.getStatus(), "未删除文档不受影响");
    }

    @Test
    void staleWatermarkFallsBackToFullScanAndDeprecatesMissing() throws Exception {
        assumeTrue(gitAvailable(), "环境无 git 可执行文件，跳过");
        git("init", "-q");
        write("docs/keep.md", "# 保留\n\n内容保留。\n");
        write("docs/gone.md", "# 将删除\n\n内容将删除。\n");
        commit("init");
        SyncOutcome initial = sync.run(source, ctx);
        source.setWatermark(initial.watermark());

        Files.delete(repoDir.resolve("docs/gone.md"));
        commit("delete gone");
        // 合法格式但不存在于仓库历史 → merge-base 失败 → 全量扫描
        source.setWatermark("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

        SyncOutcome outcome = sync.run(source, ctx);

        assertEquals(1, outcome.deprecated(), "全量扫描用差集发现源端已删除文档");
        assertEquals("deprecated", byExternal.get("3:sync:7:docs/gone.md").getStatus());
        assertEquals("active", byExternal.get("3:sync:7:docs/keep.md").getStatus());
        assertEquals(1, outcome.unchanged(), "仍存在的文档保持原条目");
    }

    @Test
    void rejectsUnsafeConfigBeforeTouchingGitHistory() throws Exception {
        assumeTrue(gitAvailable(), "环境无 git 可执行文件，跳过");
        git("init", "-q");
        source.setConfigJson("{\"repoPath\":\"" + jsonPath(repoDir)
                + "\",\"docsPath\":\"../outside\"}");
        assertThrows(DevMindException.class, () -> sync.run(source, ctx));

        source.setConfigJson("{\"repoPath\":\"" + jsonPath(repoDir)
                + "\",\"ref\":\"--upload-pack=evil\"}");
        assertThrows(DevMindException.class, () -> sync.run(source, ctx));
    }

    @Test
    void rejectsInvalidDocTypeWithoutGitRepo() {
        source.setConfigJson("{\"repoPath\":\"/tmp/nope\",\"docType\":\"UNKNOWN\"}");
        assertThrows(DevMindException.class, () -> sync.run(source, null));

        source.setConfigJson("not json");
        assertThrows(DevMindException.class, () -> sync.run(source, null));
    }

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commit(String message) throws Exception {
        git("add", "-A");
        git("-c", "user.email=test@example.com", "-c", "user.name=devmind-test",
                "commit", "-q", "-m", message);
    }

    private String git(String... args) throws Exception {
        return runGit(repoDir, args);
    }

    private static String runGit(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git 超时: " + String.join(" ", cmd));
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git 失败(" + p.exitValue() + "): " + text);
        }
        return text;
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private record SyncRef(Long id, String externalId)
            implements KnowledgeEntryRepository.SyncEntryRef {
        @Override public Long getId() { return id; }
        @Override public String getExternalId() { return externalId; }
    }
}

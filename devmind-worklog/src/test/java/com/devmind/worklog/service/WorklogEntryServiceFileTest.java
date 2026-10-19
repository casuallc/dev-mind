package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.model.WorklogRepoMemberEntity;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogRepoMemberRepository;
import com.devmind.worklog.repo.WorklogRepoRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-74 M3 {@link WorklogEntryService} 文件口径：CRUD 落仓内 entries day 文件、
 * 改期跨文件搬移、git 导入按日分组单 commit + sha 幂等、list 合计/关键字。
 * 真 git 子进程（@TempDir）；DB 层 Mockito mock 背 ConcurrentHashMap（同 WorklogRepoServiceTest 姿势）。
 */
class WorklogEntryServiceFileTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @TempDir
    Path repoRoot;

    private final Map<String, WorklogRepoEntity> repos = new ConcurrentHashMap<>();
    private final Map<String, WorklogRepoMemberEntity> members = new ConcurrentHashMap<>();
    private final AtomicLong repoIds = new AtomicLong(1);

    private WorklogRepoService repoService;
    private WorklogEntryService service;

    @BeforeEach
    void setUp() {
        WorklogRepoRepository repoRepo = mock(WorklogRepoRepository.class);
        when(repoRepo.findByRepoKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(repos.get(inv.getArgument(0))));
        when(repoRepo.save(any(WorklogRepoEntity.class))).thenAnswer(inv -> {
            WorklogRepoEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(repoIds.getAndIncrement());
            }
            repos.put(e.getRepoKey(), e);
            return e;
        });
        WorklogRepoMemberRepository memberRepo = mock(WorklogRepoMemberRepository.class);
        when(memberRepo.findByRepoIdAndUserId(anyLong(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(members.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(memberRepo.save(any(WorklogRepoMemberEntity.class))).thenAnswer(inv -> {
            WorklogRepoMemberEntity e = inv.getArgument(0);
            members.put(e.getRepoId() + ":" + e.getUserId(), e);
            return e;
        });
        WorklogUserSettingsRepository settingsRepo = mock(WorklogUserSettingsRepository.class);
        when(settingsRepo.findByUserId(anyString())).thenReturn(Optional.empty());

        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(repoRoot.toString());
        GitRemoteOps ops = new GitRemoteOps(nullIdentityProvider());
        repoService = new WorklogRepoService(props, repoRepo, memberRepo, settingsRepo, ops,
                nullIdentityProvider(), nullIdentityProvider(), nullIdentityProvider());

        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn("alice");
        service = new WorklogEntryService(repoService, identity, nullIdentityProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> nullIdentityProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private String dayFile(LocalDate date) throws Exception {
        Path f = repoRoot.resolve(WorklogRepoService.personalRepoKey("alice"))
                .resolve("alice/entries/" + date + ".md");
        return java.nio.file.Files.readString(f, StandardCharsets.UTF_8);
    }

    private static EntryRequest req(LocalDate date, String title, double hours) {
        return new EntryRequest(date, title, "正文-" + title, "DEV", hours, null, null);
    }

    @Test
    void create写文件且id为uuid() throws Exception {
        EntryView v = service.create(req(DAY, "实现仓级锁", 1.5));
        assertNotNull(v.id());
        assertTrue(v.id().matches("[0-9a-f]{32}"));
        assertEquals(1.5, v.hours());
        assertEquals("MANUAL", v.source());
        assertNotNull(v.createdAt());

        String content = dayFile(DAY);
        assertTrue(content.startsWith("# 2026-10-12 工作条目\n"));
        assertTrue(content.contains("<!-- devmind:entry id=" + v.id()));
        assertTrue(content.contains("hours=1.5"));
        assertTrue(content.contains("## 实现仓级锁"));
        assertTrue(content.contains("正文-实现仓级锁"));
    }

    @Test
    void update同日改字段保留id与preamble() throws Exception {
        EntryView v = service.create(req(DAY, "旧标题", 1.0));
        EntryView v2 = service.update(v.id(), req(DAY, "新标题", 2.0));
        assertEquals(v.id(), v2.id());
        assertEquals(v.createdAt(), v2.createdAt());
        String content = dayFile(DAY);
        assertTrue(content.contains("## 新标题"));
        assertTrue(content.contains("hours=2"));
        assertTrue(content.startsWith("# 2026-10-12 工作条目\n"));
        assertTrue(!content.contains("旧标题"));
    }

    @Test
    void update改期跨文件搬移() throws Exception {
        EntryView v = service.create(req(DAY, "搬移条目", 1.0));
        LocalDate nextDay = DAY.plusDays(1);
        EntryView moved = service.update(v.id(), req(nextDay, "搬移条目", 1.0));
        assertEquals(nextDay, moved.workDate());
        assertTrue(!dayFile(DAY).contains(v.id()));
        assertTrue(dayFile(nextDay).contains(v.id()));
        // list 按新日期归位
        EntryPage page = service.list(nextDay, nextDay, null, 0, 20);
        assertEquals(1, page.total());
    }

    @Test
    void delete按日期寻址() throws Exception {
        EntryView v = service.create(req(DAY, "待删", 0.5));
        service.delete(DAY, v.id());
        assertTrue(!dayFile(DAY).contains(v.id()));
        // 他人/不存在一律 404
        assertThrows(DevMindException.class, () -> service.delete(DAY, v.id()));
        assertThrows(DevMindException.class, () -> service.delete(DAY.plusDays(7), v.id()));
    }

    @Test
    void importGit按日分组且sha幂等() throws Exception {
        GitImportRequest req = new GitImportRequest(List.of(
                new GitImportRequest.Item(42L, "sha-a", "feat: 甲", DAY, 1.0),
                new GitImportRequest.Item(42L, "sha-b", "fix: 乙", DAY, null),
                new GitImportRequest.Item(7L, "sha-c", "chore: 丙", DAY.plusDays(1), 0.5)));
        int[] r = service.importGit("alice", req);
        assertEquals(3, r[0]);
        assertEquals(0, r[1]);
        String day1 = dayFile(DAY);
        assertTrue(day1.contains("sha=sha-a") && day1.contains("sha=sha-b"));
        assertTrue(day1.contains("source=GIT"));
        assertTrue(dayFile(DAY.plusDays(1)).contains("sha=sha-c"));
        // 重复导入全部跳过（幂等键 = 文件元数据 sha）
        int[] r2 = service.importGit("alice", req);
        assertEquals(0, r2[0]);
        assertEquals(3, r2[1]);
        assertTrue(service.importedShas("alice").containsAll(
                java.util.Set.of("42:sha-a", "42:sha-b", "7:sha-c")));
    }

    @Test
    void list排序合计与关键字() throws Exception {
        service.create(req(DAY, "甲需求开发", 1.0));
        service.create(req(DAY, "乙会议", 0.5));
        service.create(req(DAY.minusDays(1), "丙需求调研", 2.0));

        EntryPage all = service.list(DAY.minusDays(1), DAY, null, 0, 20);
        assertEquals(3, all.total());
        assertEquals(210, all.totalMinutes());
        // workDate desc 优先
        assertEquals(DAY, all.items().get(0).workDate());
        assertEquals(DAY.minusDays(1), all.items().get(2).workDate());

        EntryPage kw = service.list(DAY.minusDays(1), DAY, "需求", 0, 20);
        assertEquals(2, kw.total());
        assertEquals(180, kw.totalMinutes());

        EntryPage paged = service.list(DAY.minusDays(1), DAY, null, 1, 2);
        assertEquals(1, paged.items().size());
        assertEquals(3, paged.total());
    }

    @Test
    void 平台外追加自由文本在重写后保留() throws Exception {
        EntryView a = service.create(req(DAY, "条目甲", 1.0));
        // 人类直接编辑文件：条目甲正文尾部追加裸文本
        Path f = repoRoot.resolve(WorklogRepoService.personalRepoKey("alice"))
                .resolve("alice/entries/" + DAY + ".md");
        String edited = java.nio.file.Files.readString(f, StandardCharsets.UTF_8)
                .replace("正文-条目甲", "正文-条目甲\n\n## 人类追加\n手写补充。");
        java.nio.file.Files.writeString(f, edited, StandardCharsets.UTF_8);
        // 平台重写该文件（新建条目乙）后，自由文本随条目甲正文逐字节保留
        service.create(req(DAY, "条目乙", 0.5));
        String content = dayFile(DAY);
        assertTrue(content.contains("## 条目乙"));
        assertTrue(content.contains("## 人类追加"));
        assertTrue(content.contains("手写补充。"));
        // 条目甲正文（含自由文本）在列表视图原样可见
        EntryPage page = service.list(DAY, DAY, null, 0, 20);
        assertTrue(page.items().stream()
                .filter(i -> i.id().equals(a.id()))
                .findFirst().orElseThrow().content().contains("手写补充。"));
    }

    @Test
    void 非法entryType拒绝() {
        assertThrows(DevMindException.class, () -> service.create(
                new EntryRequest(DAY, "x", null, "HACK", 1.0, null, null)));
    }
}

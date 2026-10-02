package com.devmind.bookmark.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.bookmark.dto.BookmarkImportRequest;
import com.devmind.bookmark.dto.ImportResultView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkGroupEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-64 FR-09 导入单测（无 Spring 上下文，仓储走 FakeBookmarkRepos）。
 * 重点：树→分组映射、重复导入幂等、去重/非法地址跳过、上限 400、归属隔离。
 */
class BookmarkImportServiceTest {

    private FakeBookmarkRepos repo;
    private BookmarkImportService service;
    private String actor = "alice";

    @BeforeEach
    void setUp() {
        repo = new FakeBookmarkRepos();
        repo.users.put("alice", FakeBookmarkRepos.user("alice", UserEntity.ROLE_DEVELOPER));
        repo.users.put("bob", FakeBookmarkRepos.user("bob", UserEntity.ROLE_DEVELOPER));
        service = new BookmarkImportService(repo.bookmarks(), repo.groups(), repo.tags(), repo.rels(),
                new BookmarkOwnership(fakeIdentity()));
    }

    private IdentityService fakeIdentity() {
        return new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return actor;
            }

            @Override
            public Optional<UserEntity> currentUser() {
                return Optional.ofNullable(repo.users.get(actor));
            }
        };
    }

    private static BookmarkImportRequest.Node folder(String name, BookmarkImportRequest.Node... children) {
        return new BookmarkImportRequest.Node("folder", name, null, null, null, null, List.of(children));
    }

    private static BookmarkImportRequest.Node bm(String title, String url) {
        return new BookmarkImportRequest.Node("bookmark", null, title, url, null, null, null);
    }

    private static BookmarkImportRequest.Node bm(String title, String url, List<String> tags) {
        return new BookmarkImportRequest.Node("bookmark", null, title, url, null, tags, null);
    }

    private List<BookmarkEntity> mineBookmarks(String owner) {
        return repo.bookmarks.values().stream().filter(b -> b.getOwnerId().equals(owner)).toList();
    }

    // ---------------- 树 → 分组/收藏 ----------------

    @Test
    void importsFolderTreeAndLooseBookmarks() {
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                folder("书签栏",
                        bm("Nexus", "https://nexus.example.com"),
                        folder("监控", bm("Grafana", "https://grafana.example.com"))),
                bm("散落的", "https://loose.example.com"))));

        assertEquals(2, r.createdGroups());
        assertEquals(3, r.createdBookmarks());
        assertEquals(0, r.skippedDuplicates());
        assertEquals(0, r.skippedInvalid());

        BookmarkGroupEntity bar = repo.groups.values().stream()
                .filter(g -> g.getName().equals("书签栏")).findFirst().orElseThrow();
        BookmarkGroupEntity mon = repo.groups.values().stream()
                .filter(g -> g.getName().equals("监控")).findFirst().orElseThrow();
        assertNull(bar.getParentId());
        assertEquals(bar.getId(), mon.getParentId());

        BookmarkEntity nexus = mineBookmarks("alice").stream()
                .filter(b -> b.getTitle().equals("Nexus")).findFirst().orElseThrow();
        assertEquals(bar.getId(), nexus.getGroupId());
        BookmarkEntity grafana = mineBookmarks("alice").stream()
                .filter(b -> b.getTitle().equals("Grafana")).findFirst().orElseThrow();
        assertEquals(mon.getId(), grafana.getGroupId());
        BookmarkEntity loose = mineBookmarks("alice").stream()
                .filter(b -> b.getTitle().equals("散落的")).findFirst().orElseThrow();
        assertNull(loose.getGroupId());
        assertEquals(BookmarkEntity.STATUS_UNKNOWN, loose.getLastStatus());
    }

    @Test
    void emptyTitleFallsBackToUrl() {
        service.importTree(new BookmarkImportRequest(List.of(bm("  ", "https://x.example.com"))));
        assertEquals("https://x.example.com", mineBookmarks("alice").get(0).getTitle());
    }

    // ---------------- 幂等 / 去重 ----------------

    @Test
    void reimportSameFileIsIdempotent() {
        BookmarkImportRequest req = new BookmarkImportRequest(List.of(
                folder("书签栏", bm("Nexus", "https://nexus.example.com"))));
        service.importTree(req);
        ImportResultView again = service.importTree(req);
        assertEquals(0, again.createdGroups());
        assertEquals(0, again.createdBookmarks());
        assertEquals(1, again.skippedDuplicates());
        assertEquals(1, repo.groups.size());
        assertEquals(1, mineBookmarks("alice").size());
    }

    @Test
    void duplicateWithinBatchSkippedOnce() {
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                bm("A", "https://x.example.com"), bm("B", "https://x.example.com"))));
        assertEquals(1, r.createdBookmarks());
        assertEquals(1, r.skippedDuplicates());
    }

    @Test
    void reusesSameNameGroupUnderSameParentOnly() {
        // 已有根分组「书签栏」；导入根下「书签栏」复用它，但子层同名是另一条键
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                folder("书签栏", folder("书签栏", bm("X", "https://x.example.com"))))));
        assertEquals(2, r.createdGroups());
        ImportResultView again = service.importTree(new BookmarkImportRequest(List.of(
                folder("书签栏", bm("Y", "https://y.example.com")))));
        assertEquals(0, again.createdGroups());
        assertEquals(1, again.createdBookmarks());
    }

    // ---------------- 跳过口径 ----------------

    @Test
    void invalidUrlsSkippedNotBlocking() {
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                bm("bad-scheme", "javascript:alert(1)"),
                bm("no-host", "http://"),
                bm("good", "https://ok.example.com"))));
        assertEquals(1, r.createdBookmarks());
        assertEquals(2, r.skippedInvalid());
    }

    @Test
    void overlongFieldsTruncatedNotRejected() {
        String longTitle = "t".repeat(300);
        String longGroup = "g".repeat(200);
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                folder(longGroup, bm(longTitle, "https://x.example.com")))));
        assertEquals(1, r.createdBookmarks());
        assertEquals(256, mineBookmarks("alice").get(0).getTitle().length());
        assertEquals(128, repo.groups.values().iterator().next().getName().length());
    }

    // ---------------- 标签 ----------------

    @Test
    void firefoxTagsGetOrCreateAndAttach() {
        service.importTree(new BookmarkImportRequest(List.of(
                bm("A", "https://a.example.com", List.of("内网", "运维")),
                bm("B", "https://b.example.com", List.of("内网", "", "x".repeat(65))))));
        // 「内网」只建一次；空名与超长名跳过
        assertEquals(2, repo.tags.size());
        BookmarkTagEntity intranet = repo.tags.values().stream()
                .filter(t -> t.getName().equals("内网")).findFirst().orElseThrow();
        long intranetRels = repo.rels.values().stream().filter(rel -> rel.getTagId().equals(intranet.getId())).count();
        assertEquals(2, intranetRels);
        assertEquals(3, repo.rels.size());
    }

    // ---------------- 上限 ----------------

    @Test
    void depthLimitRejected() {
        // MAX_DEPTH+1 层文件夹：第 9 层抛 400（生产 @Transactional 整体回滚；fake 无事务语义，
        // 这里只断言书签一条都没落库——抛错点在遇到任何书签之前）
        BookmarkImportRequest.Node deep = bm("X", "https://x.example.com");
        for (int i = 0; i <= BookmarkImportService.MAX_DEPTH; i++) {
            deep = folder("d" + i, deep);
        }
        BookmarkImportRequest.Node payload = deep;
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.importTree(new BookmarkImportRequest(List.of(payload))));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(repo.bookmarks.isEmpty());
    }

    @Test
    void bookmarkCountLimitRejected() {
        List<BookmarkImportRequest.Node> nodes = new ArrayList<>();
        for (int i = 0; i < BookmarkImportService.MAX_BOOKMARKS + 1; i++) {
            nodes.add(bm("b" + i, "https://x.example.com/" + i));
        }
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.importTree(new BookmarkImportRequest(nodes)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void unknownNodeTypeRejected() {
        DevMindException e = assertThrows(DevMindException.class, () ->
                service.importTree(new BookmarkImportRequest(List.of(
                        new BookmarkImportRequest.Node("separator", null, null, null, null, null, null)))));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    // ---------------- 归属 ----------------

    @Test
    void sameUrlUnderOtherOwnerDoesNotBlock() {
        actor = "bob";
        service.importTree(new BookmarkImportRequest(List.of(bm("B 的", "https://x.example.com"))));
        actor = "alice";
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                bm("A 的", "https://x.example.com"))));
        assertEquals(1, r.createdBookmarks());
        assertEquals(1, mineBookmarks("alice").size());
        assertEquals(1, mineBookmarks("bob").size());
    }

    @Test
    void groupsAreOwnerScoped() {
        actor = "bob";
        service.importTree(new BookmarkImportRequest(List.of(folder("书签栏", bm("X", "https://x.example.com")))));
        actor = "alice";
        // alice 导入同名分组：不复用 bob 的，自建一条
        ImportResultView r = service.importTree(new BookmarkImportRequest(List.of(
                folder("书签栏", bm("Y", "https://y.example.com")))));
        assertEquals(1, r.createdGroups());
        assertEquals(2, repo.groups.size());
        assertTrue(repo.groups.values().stream().allMatch(g -> g.getOwnerId().equals("alice") || g.getOwnerId().equals("bob")));
    }
}

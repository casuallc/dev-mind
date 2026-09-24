package com.devmind.bookmark.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.bookmark.config.BookmarkCipher;
import com.devmind.bookmark.config.BookmarkProperties;
import com.devmind.bookmark.dto.BookmarkAccountRequest;
import com.devmind.bookmark.dto.BookmarkRequest;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.MoveBookmarksRequest;
import com.devmind.bookmark.dto.SecretView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-64 FR-01/02/05/08 单测（无 Spring 上下文）：仓储走内存 fake。
 * 重点覆盖归属四象限（owner/他人 × 读/写）+ ADMIN 只读、账号密文与脱敏、批量转移与分组归属。
 */
class BookmarkServiceTest {

    private FakeBookmarkRepos repo;
    private BookmarkService service;
    private BookmarkCipher cipher;
    private String actor = "alice";
    private boolean admin;

    @BeforeEach
    void setUp() {
        repo = new FakeBookmarkRepos();
        repo.users.put("alice", FakeBookmarkRepos.user("alice", UserEntity.ROLE_DEVELOPER));
        repo.users.put("bob", FakeBookmarkRepos.user("bob", UserEntity.ROLE_DEVELOPER));
        repo.users.put("boss", FakeBookmarkRepos.user("boss", UserEntity.ROLE_ADMIN));

        BookmarkProperties props = new BookmarkProperties();
        // 显式给密钥：单测不落 data/bookmark-crypto.key
        props.setCryptoKey("devmind-bookmark-unit-test-key");

        cipher = new BookmarkCipher(props);
        cipher.init();

        BookmarkOwnership ownership = new BookmarkOwnership(fakeIdentity());
        BookmarkViews views = new BookmarkViews(repo.rels(), repo.tags(), repo.accounts());
        BookmarkProbeService probe = new BookmarkProbeService(repo.bookmarks(), props, ownership);
        service = new BookmarkService(repo.bookmarks(), repo.groups(), repo.tags(), repo.rels(),
                repo.accounts(), repo.shares(), cipher, ownership, views, probe);
    }

    private IdentityService fakeIdentity() {
        return new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return actor;
            }

            @Override
            public Optional<UserEntity> currentUser() {
                return admin ? Optional.of(repo.users.get("boss")) : Optional.empty();
            }
        };
    }

    private static BookmarkRequest req(String title, String url) {
        return new BookmarkRequest(title, url, null, null, null, null, null, null);
    }

    private Long newGroup(String name, Long parentId) {
        return serviceGroup().create(new com.devmind.bookmark.dto.BookmarkGroupRequest(name, parentId, null)).id();
    }

    private BookmarkGroupService serviceGroup() {
        BookmarkProperties props = new BookmarkProperties();
        props.setCryptoKey("devmind-bookmark-unit-test-key");
        BookmarkOwnership ownership = new BookmarkOwnership(fakeIdentity());
        return new BookmarkGroupService(repo.groups(), repo.bookmarks(), repo.rels(), repo.accounts(),
                repo.shares(), ownership);
    }

    // ---------------- FR-01 ----------------

    @Test
    void createTrimsAndNormalizesUrl() {
        BookmarkView v = service.create(req("  Nexus  ", "  https://nexus.example.com/repo  "));
        assertEquals("Nexus", v.title());
        assertEquals("https://nexus.example.com/repo", v.url());
        assertEquals(BookmarkEntity.STATUS_UNKNOWN, v.lastStatus());
        assertNotNull(v.createdAt());
    }

    @Test
    void createRejectsNonHttpScheme() {
        for (String bad : List.of("file:///etc/passwd", "ftp://x/y", "javascript:alert(1)", "nexus.example.com")) {
            DevMindException e = assertThrows(DevMindException.class, () -> service.create(req("x", bad)), bad);
            assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        }
    }

    @Test
    void sameUrlTwiceIsAllowed() {
        // FR-01：同 URL 不强制拦截（端口/参数不同是合法场景），只在保存时提示
        service.create(req("A", "https://x.example.com/a"));
        service.create(req("B", "https://x.example.com/a"));
        assertEquals(2, service.list(null, null, null, null, null).size());
    }

    @Test
    void keywordAndStatusFilter() {
        service.create(new BookmarkRequest("Nexus 私服", "https://nexus.example.com",
                "构件仓库", null, null, null, null, null));
        service.create(new BookmarkRequest("Jira", "https://jira.example.com", null, null, null, null, null, null));
        assertEquals(1, service.list(null, null, null, "构件", null).size());
        assertEquals(1, service.list(null, null, null, "jira.example", null).size());
        // 都是 UNKNOWN，状态筛 ALL/空 不筛
        assertEquals(2, service.list(null, null, null, null, "ALL").size());
        assertEquals(2, service.list(null, null, null, null, "UNKNOWN").size());
        assertEquals(0, service.list(null, null, null, null, "OK").size());
    }

    // ---------------- FR-08 归属 ----------------

    @Test
    void otherUserGetsNotFoundOnEveryEndpoint() {
        BookmarkView mine = service.create(req("内网控制台", "https://console.example.com"));
        Long id = Long.valueOf(mine.id());

        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> service.get(id)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> service.update(id, req("改", "https://evil.example.com"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> service.delete(id)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> service.visit(id)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> service.probe(id)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> service.move(new MoveBookmarksRequest(List.of(id), null))).getErrorCode());
        // 列表里也看不到
        assertTrue(service.list(null, null, null, null, null).isEmpty());
        // 越权文案就是「不存在」的原文案——不能出现任何「无权限」措辞（不暴露存在性）
        String forbiddenMsg = assertThrows(DevMindException.class, () -> service.get(id)).getMessage();
        assertEquals("收藏不存在: " + id, forbiddenMsg);
    }

    @Test
    void adminCanReadButNotWrite() {
        BookmarkView mine = service.create(req("内网控制台", "https://console.example.com"));
        Long id = Long.valueOf(mine.id());

        actor = "boss";
        admin = true;
        assertEquals("内网控制台", service.get(id).title(), "ADMIN 可读（排障语义）");
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> service.update(id, req("改", "https://evil.example.com"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> service.delete(id)).getErrorCode());
    }

    @Test
    void groupMustBelongToMe() {
        Long g = newGroup("我的组", null);
        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> service.create(new BookmarkRequest("x", "https://x.example.com", null, g, null, null, null, null)))
                .getErrorCode(), "挂到别人的分组 = 分组不存在");
    }

    // ---------------- FR-02 分组与转移 ----------------

    @Test
    void groupListFilterIncludesSubtree() {
        Long root = newGroup("环境", null);
        Long child = newGroup("测试", root);
        BookmarkView inRoot = service.create(new BookmarkRequest("A", "https://a.example.com", null, root, null, null, null, null));
        BookmarkView inChild = service.create(new BookmarkRequest("B", "https://b.example.com", null, child, null, null, null, null));
        BookmarkView loose = service.create(req("C", "https://c.example.com"));

        assertEquals(2, service.list(root, null, null, null, null).size(), "点父分组看到整棵子树");
        assertEquals(1, service.list(child, null, null, null, null).size());
        assertEquals(1, service.list(null, true, null, null, null).size(), "未分组单列");
        assertEquals("C", service.list(null, true, null, null, null).get(0).title());
        assertNotNull(inRoot.id());
        assertNotNull(inChild.id());
        assertNotNull(loose.id());
    }

    @Test
    void moveBatchToUngrouped() {
        Long g = newGroup("临时", null);
        BookmarkView a = service.create(new BookmarkRequest("A", "https://a.example.com", null, g, null, null, null, null));
        BookmarkView b = service.create(new BookmarkRequest("B", "https://b.example.com", null, g, null, null, null, null));
        assertEquals(2, service.move(new MoveBookmarksRequest(List.of(Long.valueOf(a.id()), Long.valueOf(b.id())), null)));
        assertEquals(2, service.list(null, true, null, null, null).size());
        assertEquals(0, service.list(g, null, null, null, null).size());
    }

    @Test
    void deleteGroupDefaultKeepsBookmarksUngrouped() {
        BookmarkGroupService groups = serviceGroup();
        Long g = groups.create(new com.devmind.bookmark.dto.BookmarkGroupRequest("临时", null, null)).id();
        service.create(new BookmarkRequest("A", "https://a.example.com", null, g, null, null, null, null));

        groups.delete(g, false);
        assertEquals(0, groups.tree().size());
        assertEquals(1, service.list(null, true, null, null, null).size(), "默认档：收藏落未分组而不是被删");
    }

    @Test
    void deleteGroupCascadeRemovesBookmarks() {
        BookmarkGroupService groups = serviceGroup();
        Long g = groups.create(new com.devmind.bookmark.dto.BookmarkGroupRequest("临时", null, null)).id();
        service.create(new BookmarkRequest("A", "https://a.example.com", null, g, null, null, null, null));

        groups.delete(g, true);
        assertTrue(service.list(null, null, null, null, null).isEmpty());
        assertTrue(repo.bookmarkMap().isEmpty());
        assertTrue(groups.tree().isEmpty(), "级联档连子分组一起删");
    }

    @Test
    void deleteParentPromotesChildrenAndDetachesOnlyOwnBookmarks() {
        BookmarkGroupService groups = serviceGroup();
        Long root = groups.create(new com.devmind.bookmark.dto.BookmarkGroupRequest("环境", null, null)).id();
        Long child = groups.create(new com.devmind.bookmark.dto.BookmarkGroupRequest("测试", root, null)).id();
        service.create(new BookmarkRequest("父子", "https://root.example.com", null, root, null, null, null, null));
        service.create(new BookmarkRequest("子子", "https://child.example.com", null, child, null, null, null, null));

        groups.delete(root, false);
        List<com.devmind.bookmark.dto.BookmarkGroupView> tree = groups.tree();
        assertEquals(1, tree.size(), "子分组被上提为根");
        assertEquals("测试", tree.get(0).name());
        assertEquals(1, service.list(null, true, null, null, null).size(), "只有被删组自己的收藏落未分组");
        assertEquals(1, service.list(child, null, null, null, null).size(), "存活子分组里的收藏原地不动");
    }

    // ---------------- FR-03 标签 ----------------

    @Test
    void tagFilterIsAndSemanticsAndScopedToOwner() {
        BookmarkTagService tags = new BookmarkTagService(repo.tags(), repo.rels(), new BookmarkOwnership(fakeIdentity()));
        Long t1 = tags.create("内网").id();
        Long t2 = tags.create("运维").id();
        BookmarkView both = service.create(new BookmarkRequest("A", "https://a.example.com",
                null, null, List.of(t1, t2), null, null, null));
        service.create(new BookmarkRequest("B", "https://b.example.com", null, null, List.of(t1), null, null, null));

        assertEquals(2, service.list(null, null, List.of(t1), null, null).size());
        assertEquals(1, service.list(null, null, List.of(t1, t2), null, null).size(), "多标签为与语义");
        assertEquals(2, both.tags().size());

        // 他人标签不可挂
        actor = "bob";
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> service.create(new BookmarkRequest("C", "https://c.example.com",
                        null, null, List.of(t1), null, null, null))).getErrorCode());
    }

    @Test
    void tagCreateIsIdempotentByName() {
        BookmarkTagService tags = new BookmarkTagService(repo.tags(), repo.rels(), new BookmarkOwnership(fakeIdentity()));
        Long first = tags.create(" 运维 ").id();
        assertEquals(first, tags.create("运维").id(), "同名创建幂等返回既有标签");
        assertEquals(1, tags.list().size());
    }

    @Test
    void deleteTagKeepsBookmarks() {
        BookmarkTagService tags = new BookmarkTagService(repo.tags(), repo.rels(), new BookmarkOwnership(fakeIdentity()));
        Long t = tags.create("运维").id();
        service.create(new BookmarkRequest("A", "https://a.example.com", null, null, List.of(t), null, null, null));

        tags.delete(t);
        assertEquals(1, service.list(null, null, null, null, null).size(), "删标签不动收藏");
        assertTrue(service.list(null, null, null, null, null).get(0).tags().isEmpty());
    }

    // ---------------- FR-05 账号 ----------------

    @Test
    void accountPasswordStoredEncryptedAndMaskedInList() {
        BookmarkView v = service.create(new BookmarkRequest("控制台", "https://console.example.com", null, null, null,
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "P@ssw0rd", null, "生产", 0)),
                null, null));
        Long id = Long.valueOf(v.id());

        // 出参恒脱敏
        assertEquals(BookmarkViews.PASSWORD_MASK, v.accounts().get(0).passwordMasked());
        assertTrue(v.accounts().get(0).hasPassword());
        assertFalse(v.accounts().get(0).passwordMasked().contains("P@ssw0rd"));

        // 库里是密文
        String enc = repo.accounts.values().iterator().next().getPasswordEnc();
        assertNotNull(enc);
        assertTrue(cipher.isEncrypted(enc));
        assertFalse(enc.contains("P@ssw0rd"));

        // 明文只能按次取
        String aid = v.accounts().get(0).id();
        SecretView secret = service.secret(id, aid);
        assertEquals("P@ssw0rd", secret.password());

        // 他人取不到明文
        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> service.secret(id, aid)).getErrorCode());
    }

    @Test
    void accountGroupSubmitCreatesUpdatesAndDeletes() {
        BookmarkView v = service.create(new BookmarkRequest("控制台", "https://console.example.com", null, null, null,
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "pw1", null, null, 0),
                        new BookmarkAccountRequest(null, "只读", "viewer", null, null, null, 1)),
                null, null));
        Long id = Long.valueOf(v.id());
        String keepId = v.accounts().stream().filter(a -> "管理员".equals(a.label())).findFirst().orElseThrow().id();
        String dropId = v.accounts().stream().filter(a -> "只读".equals(a.label())).findFirst().orElseThrow().id();

        // 整组提交：改 label + 留空密码（不修改） + 保留一条 + 新增一条
        BookmarkView updated = service.update(id, new BookmarkRequest("控制台", "https://console.example.com",
                null, null, null,
                List.of(new BookmarkAccountRequest(keepId, "管理员账号", "admin", null, null, "改了备注", 0),
                        new BookmarkAccountRequest(null, "应急", "emergency", "pw2", null, null, 1)),
                null, null));

        assertEquals(2, updated.accounts().size());
        assertTrue(updated.accounts().stream().noneMatch(a -> a.id().equals(dropId)), "未出现的账号即删除");
        assertEquals("管理员账号", updated.accounts().get(0).label());
        assertEquals("pw1", service.secret(id, keepId).password(), "密码留空 = 不修改");
        assertEquals("pw2", service.secret(id, updated.accounts().get(1).id()).password());
    }

    @Test
    void clearPasswordExplicitly() {
        BookmarkView v = service.create(new BookmarkRequest("控制台", "https://console.example.com", null, null, null,
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "pw1", null, null, 0)), null, null));
        Long id = Long.valueOf(v.id());
        String aid = v.accounts().get(0).id();

        BookmarkView updated = service.update(id, new BookmarkRequest("控制台", "https://console.example.com",
                null, null, null,
                List.of(new BookmarkAccountRequest(aid, "管理员", "admin", null, Boolean.TRUE, null, 0)),
                null, null));
        assertFalse(updated.accounts().get(0).hasPassword());
        assertNull(service.secret(id, aid).password());
        assertNull(repo.accounts.values().iterator().next().getPasswordEnc());
    }

    @Test
    void accountsDeletedWithBookmark() {
        BookmarkView v = service.create(new BookmarkRequest("控制台", "https://console.example.com", null, null, null,
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "pw1", null, null, 0)), null, null));
        service.delete(Long.valueOf(v.id()));
        assertTrue(repo.accounts.isEmpty(), "账号随收藏级联删除");
    }

    // ---------------- FR-06 ----------------

    @Test
    void visitRecordsTimestamp() {
        BookmarkView v = service.create(req("A", "https://a.example.com"));
        Long id = Long.valueOf(v.id());
        assertNull(service.get(id).lastVisitedAt());
        service.visit(id);
        assertNotNull(service.get(id).lastVisitedAt());
    }
}

package com.devmind.bookmark.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.bookmark.config.BookmarkCipher;
import com.devmind.bookmark.config.BookmarkProperties;
import com.devmind.bookmark.dto.BookmarkAccountRequest;
import com.devmind.bookmark.dto.BookmarkGroupRequest;
import com.devmind.bookmark.dto.BookmarkRequest;
import com.devmind.bookmark.dto.BookmarkShareRequest;
import com.devmind.bookmark.dto.BookmarkShareView;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.CopySharedRequest;
import com.devmind.bookmark.dto.SharedWithMeView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-64 FR-07 分享：只读引用语义、分组分享含子树与后续新增、撤销即不可见、密码恒不分享、复制成自有数据。
 */
class BookmarkShareServiceTest {

    private FakeBookmarkRepos repo;
    private BookmarkService bookmarks;
    private BookmarkGroupService groups;
    private BookmarkShareService shares;
    private BookmarkOwnership ownership;
    private String actor = "alice";

    @BeforeEach
    void setUp() {
        repo = new FakeBookmarkRepos();
        repo.users.put("alice", FakeBookmarkRepos.user("alice", UserEntity.ROLE_DEVELOPER));
        repo.users.put("bob", FakeBookmarkRepos.user("bob", UserEntity.ROLE_DEVELOPER));

        BookmarkProperties props = new BookmarkProperties();
        props.setCryptoKey("devmind-bookmark-unit-test-key");
        BookmarkCipher cipher = new BookmarkCipher(props);
        cipher.init();
        ownership = new BookmarkOwnership(fakeIdentity());
        BookmarkViews views = new BookmarkViews(repo.rels(), repo.tags(), repo.accounts());
        BookmarkProbeService probe = new BookmarkProbeService(repo.bookmarks(), props, ownership);
        bookmarks = new BookmarkService(repo.bookmarks(), repo.groups(), repo.tags(), repo.rels(),
                repo.accounts(), repo.shares(), cipher, ownership, views, probe);
        groups = new BookmarkGroupService(repo.groups(), repo.bookmarks(), repo.rels(), repo.accounts(),
                repo.shares(), ownership);
        shares = new BookmarkShareService(repo.shares(), repo.bookmarks(), repo.groups(), repo.tags(),
                repo.rels(), repo.users(), ownership, views);
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

    private Long createGroup(String name, Long parentId) {
        return groups.create(new BookmarkGroupRequest(name, parentId, null)).id();
    }

    private Long createBookmark(String title, String url, Long groupId, List<Long> tagIds, List<BookmarkAccountRequest> accounts) {
        return Long.valueOf(bookmarks.create(new BookmarkRequest(title, url, null, groupId, tagIds, accounts, null, null)).id());
    }

    private List<BookmarkView> seenByBob() {
        actor = "bob";
        List<SharedWithMeView> views = shares.sharedWithMe();
        assertTrue(views.size() <= 1);
        return views.isEmpty() ? List.of() : views.get(0).bookmarks();
    }

    // ---------------- 单条分享 ----------------

    @Test
    void sharedBookmarkVisibleToTargetWithPasswordStripped() {
        Long id = createBookmark("内网控制台", "https://console.example.com", null, null,
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "P@ssw0rd", null, null, 0)));
        BookmarkShareView share = shares.create(new BookmarkShareRequest(id, null, "bob"));
        assertEquals("bob", share.targetUser());
        assertEquals("内网控制台", share.bookmarkTitle());

        List<BookmarkView> visible = seenByBob();
        assertEquals(1, visible.size());
        BookmarkView v = visible.get(0);
        assertEquals("内网控制台", v.title());
        assertEquals(1, v.accounts().size());
        assertEquals("管理员", v.accounts().get(0).label());
        assertEquals("admin", v.accounts().get(0).username(), "label/username 可见");
        assertNull(v.accounts().get(0).passwordMasked(), "密码字段整体剔除（连掩码都不给）");
        assertTrue(v.accounts().get(0).hasPassword(), "但如实告知存在密码");
    }

    @Test
    void receiverCannotReachMainEndpoints() {
        Long id = createBookmark("内网控制台", "https://console.example.com", null, null, null);
        shares.create(new BookmarkShareRequest(id, null, "bob"));

        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> bookmarks.get(id)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> bookmarks.update(id, new BookmarkRequest("改", "https://evil.example.com", null, null, null, null, null, null)))
                .getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> bookmarks.delete(id)).getErrorCode());
        // 也不可再分享（他对这条没有 owner 权）
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(id, null, "alice"))).getErrorCode());
    }

    @Test
    void revokeImmediatelyHides() {
        Long id = createBookmark("内网控制台", "https://console.example.com", null, null, null);
        Long shareId = shares.create(new BookmarkShareRequest(id, null, "bob")).id();
        assertEquals(1, seenByBob().size());

        actor = "alice";
        shares.delete(shareId);
        assertTrue(seenByBob().isEmpty(), "撤销即不可见");
        assertTrue(shares.listMine().isEmpty());
    }

    @Test
    void sourceEditReflectsToReceiver() {
        Long id = createBookmark("旧名", "https://console.example.com", null, null, null);
        shares.create(new BookmarkShareRequest(id, null, "bob"));
        assertEquals("旧名", seenByBob().get(0).title());

        actor = "alice";
        bookmarks.update(id, new BookmarkRequest("新名", "https://console.example.com/new", "改了",
                null, null, null, null, null));
        assertEquals("新名", seenByBob().get(0).title(), "只读引用：源变更实时反映，不留快照");
    }

    // ---------------- 分组分享 ----------------

    @Test
    void groupShareCoversSubtreeAndLaterAdditions() {
        Long root = createGroup("环境", null);
        Long child = createGroup("测试", root);
        createBookmark("根内", "https://root.example.com", root, null, null);
        createBookmark("子内", "https://child.example.com", child, null, null);
        createBookmark("未分组", "https://loose.example.com", null, null, null);

        shares.create(new BookmarkShareRequest(null, root, "bob"));
        List<BookmarkView> visible = seenByBob();
        assertEquals(2, visible.size(), "分组分享含子分组，未分组的看不见");

        // 接收方能看到分享者的分组树结构
        actor = "bob";
        List<SharedWithMeView> all = shares.sharedWithMe();
        assertEquals(1, all.get(0).groups().size());
        assertEquals("环境", all.get(0).groups().get(0).name());
        assertEquals(1, all.get(0).groups().get(0).children().size());
        assertEquals("测试", all.get(0).groups().get(0).children().get(0).name());

        // 后续新增自动进入分享范围
        actor = "alice";
        createBookmark("新增", "https://new.example.com", child, null, null);
        assertEquals(3, seenByBob().size(), "新增收藏自动进入分享范围");
    }

    @Test
    void groupShareRevokedWhenGroupDeleted() {
        Long root = createGroup("环境", null);
        createBookmark("根内", "https://root.example.com", root, null, null);
        shares.create(new BookmarkShareRequest(null, root, "bob"));
        assertEquals(1, seenByBob().size());

        actor = "alice";
        groups.delete(root, false);
        assertTrue(seenByBob().isEmpty(), "分组没了，分享边一并撤销");
    }

    // ---------------- 复制 ----------------

    @Test
    void copyBecomesOwnDataAndSurvivesSourceDeletion() {
        Long tagId = new BookmarkTagService(repo.tags(), repo.rels(), ownership).create("运维").id();
        Long src = createBookmark("内网控制台", "https://console.example.com", null, List.of(tagId),
                List.of(new BookmarkAccountRequest(null, "管理员", "admin", "P@ssw0rd", null, null, 0)));
        shares.create(new BookmarkShareRequest(src, null, "bob"));

        actor = "bob";
        Long myGroup = createGroup("我的组", null);
        BookmarkView copy = shares.copy(new CopySharedRequest(src, myGroup));
        assertNotNull(copy);
        assertEquals("内网控制台", copy.title());
        assertEquals("https://console.example.com", copy.url());
        assertEquals(myGroup, copy.groupId(), "落自己选的分组");
        assertTrue(copy.accounts().isEmpty(), "不含账号密码");
        assertEquals(1, copy.tags().size(), "标签按名字复制");
        assertEquals("运维", copy.tags().get(0).name());
        assertTrue(repo.tags.values().stream().anyMatch(t -> "bob".equals(t.getOwnerId()) && "运维".equals(t.getName())),
                "为接收方建了同名标签");

        // 源删除不影响副本
        actor = "alice";
        bookmarks.delete(src);
        actor = "bob";
        assertEquals(1, bookmarks.list(null, null, null, null, null).size());
        assertEquals("内网控制台", bookmarks.list(null, null, null, null, null).get(0).title());
    }

    @Test
    void copyRejectsBookmarkNotSharedWithMe() {
        Long src = createBookmark("私藏", "https://secret.example.com", null, null, null);
        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> shares.copy(new CopySharedRequest(src, null))).getErrorCode());
    }

    @Test
    void copyTargetGroupMustBeMine() {
        Long src = createBookmark("内网控制台", "https://console.example.com", null, null, null);
        Long aliceGroup = createGroup("alice 的组", null);
        shares.create(new BookmarkShareRequest(src, null, "bob"));

        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> shares.copy(new CopySharedRequest(src, aliceGroup))).getErrorCode(),
                "不能把副本落到别人的分组");
    }

    // ---------------- 参数与判重 ----------------

    @Test
    void createValidatesTargetAndShape() {
        Long id = createBookmark("A", "https://a.example.com", null, null, null);
        Long g = createGroup("G", null);

        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(id, g, "bob"))).getErrorCode(), "二选一");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(null, null, "bob"))).getErrorCode(), "必须选一个");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(id, null, "alice"))).getErrorCode(), "不能分享给自己");
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(id, null, "nobody"))).getErrorCode(), "平台内用户才可分享");
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(99999L, null, "bob"))).getErrorCode(), "只能分享自己的收藏");
    }

    @Test
    void duplicateShareIsConflict() {
        Long id = createBookmark("A", "https://a.example.com", null, null, null);
        shares.create(new BookmarkShareRequest(id, null, "bob"));
        assertEquals(ErrorCode.CONFLICT, assertThrows(DevMindException.class,
                () -> shares.create(new BookmarkShareRequest(id, null, "bob"))).getErrorCode());
        assertEquals(1, shares.listMine().size());
    }

    @Test
    void onlyOwnerCanRevoke() {
        Long id = createBookmark("A", "https://a.example.com", null, null, null);
        Long shareId = shares.create(new BookmarkShareRequest(id, null, "bob")).id();
        actor = "bob";
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(DevMindException.class, () -> shares.delete(shareId)).getErrorCode());
        assertTrue(shares.listMine().isEmpty(), "撤销权只在分享者手里");

        // 分享边仍在：alice 这边还看得见
        actor = "alice";
        assertEquals(1, shares.listMine().size());
    }
}

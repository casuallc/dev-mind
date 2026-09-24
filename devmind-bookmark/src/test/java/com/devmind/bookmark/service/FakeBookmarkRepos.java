package com.devmind.bookmark.service;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.bookmark.model.BookmarkAccountEntity;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkGroupEntity;
import com.devmind.bookmark.model.BookmarkShareEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
import com.devmind.bookmark.repo.BookmarkAccountRepository;
import com.devmind.bookmark.repo.BookmarkGroupRepository;
import com.devmind.bookmark.repo.BookmarkRepository;
import com.devmind.bookmark.repo.BookmarkShareRepository;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkTagRepository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存 fake 仓储（对照 devmind-attachment/chat 的 JDK 动态代理先例，全仓无 @SpringBootTest）。
 * 覆盖 bookmark 模块六个仓储 + auth 的 UserRepository，够单测跑通 CRUD/归属/分享全链路。
 */
class FakeBookmarkRepos {

    final Map<Long, BookmarkEntity> bookmarks = new LinkedHashMap<>();
    final Map<Long, BookmarkGroupEntity> groups = new LinkedHashMap<>();
    final Map<Long, BookmarkTagEntity> tags = new LinkedHashMap<>();
    final Map<Long, BookmarkTagRelEntity> rels = new LinkedHashMap<>();
    final Map<Long, BookmarkAccountEntity> accounts = new LinkedHashMap<>();
    final Map<Long, BookmarkShareEntity> shares = new LinkedHashMap<>();
    final Map<String, UserEntity> users = new LinkedHashMap<>();

    private final AtomicLong seq = new AtomicLong();

    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler));
    }

    // ---------------- bookmark ----------------

    BookmarkRepository bookmarks() {
        return proxy(BookmarkRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkEntity e = (BookmarkEntity) a[0];
                if (e.getId() == null) {
                    e.setId(seq.incrementAndGet());
                }
                bookmarks.put(e.getId(), e);
                yield e;
            }
            case "findById" -> Optional.ofNullable(bookmarks.get((Long) a[0]));
            case "findByIdAndOwnerId" -> Optional.ofNullable(bookmarks.get((Long) a[0]))
                    .filter(e -> e.getOwnerId().equals(a[1]));
            case "findByOwnerIdOrderBySortOrderAscCreatedAtDesc" -> mine((String) a[0]);
            case "findByGroupId" -> bookmarks.values().stream()
                    .filter(e -> ((Long) a[0]).equals(e.getGroupId())).toList();
            case "findByGroupIdAndOwnerId" -> bookmarks.values().stream()
                    .filter(e -> ((Long) a[0]).equals(e.getGroupId()) && e.getOwnerId().equals(a[1])).toList();
            case "countByGroupId" -> bookmarks.values().stream()
                    .filter(e -> ((Long) a[0]).equals(e.getGroupId())).count();
            case "findByOwnerIdAndUrl" -> bookmarks.values().stream()
                    .filter(e -> e.getOwnerId().equals(a[0]) && e.getUrl().equals(a[1])).toList();
            case "delete" -> {
                bookmarks.remove(((BookmarkEntity) a[0]).getId());
                yield null;
            }
            case "deleteById" -> {
                bookmarks.remove((Long) a[0]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private List<BookmarkEntity> mine(String ownerId) {
        return bookmarks.values().stream()
                .filter(e -> e.getOwnerId().equals(ownerId))
                .sorted(Comparator.comparingInt(BookmarkEntity::getSortOrder)
                        .thenComparing(Comparator.comparingLong(BookmarkEntity::getId).reversed()))
                .toList();
    }

    // ---------------- group ----------------

    BookmarkGroupRepository groups() {
        return proxy(BookmarkGroupRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkGroupEntity g = (BookmarkGroupEntity) a[0];
                if (g.getId() == null) {
                    g.setId(seq.incrementAndGet());
                }
                groups.put(g.getId(), g);
                yield g;
            }
            case "findById" -> Optional.ofNullable(groups.get((Long) a[0]));
            case "findByIdAndOwnerId" -> Optional.ofNullable(groups.get((Long) a[0]))
                    .filter(g -> g.getOwnerId().equals(a[1]));
            case "findByOwnerIdOrderBySortOrderAscIdAsc" -> groups.values().stream()
                    .filter(g -> g.getOwnerId().equals(a[0]))
                    .sorted(Comparator.comparingInt(BookmarkGroupEntity::getSortOrder)
                            .thenComparingLong(BookmarkGroupEntity::getId))
                    .toList();
            case "findByParentId" -> groups.values().stream()
                    .filter(g -> java.util.Objects.equals(g.getParentId(), a[0])).toList();
            case "delete" -> {
                groups.remove(((BookmarkGroupEntity) a[0]).getId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    // ---------------- tag ----------------

    BookmarkTagRepository tags() {
        return proxy(BookmarkTagRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkTagEntity t = (BookmarkTagEntity) a[0];
                if (t.getId() == null) {
                    t.setId(seq.incrementAndGet());
                }
                tags.put(t.getId(), t);
                yield t;
            }
            case "findById" -> Optional.ofNullable(tags.get((Long) a[0]));
            case "findByIdAndOwnerId" -> Optional.ofNullable(tags.get((Long) a[0]))
                    .filter(t -> t.getOwnerId().equals(a[1]));
            case "findByOwnerIdAndName" -> tags.values().stream()
                    .filter(t -> t.getOwnerId().equals(a[0]) && t.getName().equals(a[1])).findFirst();
            case "findByOwnerIdOrderByNameAsc" -> tags.values().stream()
                    .filter(t -> t.getOwnerId().equals(a[0]))
                    .sorted(Comparator.comparing(BookmarkTagEntity::getName)).toList();
            case "findAllById" -> ((Collection<Long>) a[0]).stream()
                    .map(tags::get).filter(java.util.Objects::nonNull).toList();
            case "delete" -> {
                tags.remove(((BookmarkTagEntity) a[0]).getId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    BookmarkTagRelRepository rels() {
        return proxy(BookmarkTagRelRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkTagRelEntity r = (BookmarkTagRelEntity) a[0];
                if (r.getId() == null) {
                    r.setId(seq.incrementAndGet());
                }
                rels.put(r.getId(), r);
                yield r;
            }
            case "findByBookmarkId" -> rels.values().stream()
                    .filter(r -> r.getBookmarkId().equals(a[0])).toList();
            case "findByBookmarkIdIn" -> rels.values().stream()
                    .filter(r -> ((Collection<Long>) a[0]).contains(r.getBookmarkId())).toList();
            case "findByTagId" -> rels.values().stream()
                    .filter(r -> r.getTagId().equals(a[0])).toList();
            case "findAll" -> new ArrayList<>(rels.values());
            case "deleteByBookmarkId" -> {
                rels.values().removeIf(r -> r.getBookmarkId().equals(a[0]));
                yield null;
            }
            case "deleteByTagId" -> {
                rels.values().removeIf(r -> r.getTagId().equals(a[0]));
                yield null;
            }
            case "flush" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    // ---------------- account ----------------

    BookmarkAccountRepository accounts() {
        return proxy(BookmarkAccountRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkAccountEntity x = (BookmarkAccountEntity) a[0];
                if (x.getId() == null) {
                    x.setId(seq.incrementAndGet());
                }
                accounts.put(x.getId(), x);
                yield x;
            }
            case "findByBookmarkIdOrderBySortOrderAscIdAsc" -> accounts.values().stream()
                    .filter(x -> x.getBookmarkId().equals(a[0]))
                    .sorted(Comparator.comparingInt(BookmarkAccountEntity::getSortOrder)
                            .thenComparingLong(BookmarkAccountEntity::getId))
                    .toList();
            case "findByBookmarkIdIn" -> accounts.values().stream()
                    .filter(x -> ((Collection<Long>) a[0]).contains(x.getBookmarkId())).toList();
            case "findByIdAndBookmarkId" -> Optional.ofNullable(accounts.get((Long) a[0]))
                    .filter(x -> x.getBookmarkId().equals(a[1]));
            case "deleteByBookmarkId" -> {
                accounts.values().removeIf(x -> x.getBookmarkId().equals(a[0]));
                yield null;
            }
            case "delete" -> {
                accounts.remove(((BookmarkAccountEntity) a[0]).getId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    // ---------------- share ----------------

    BookmarkShareRepository shares() {
        return proxy(BookmarkShareRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> {
                BookmarkShareEntity s = (BookmarkShareEntity) a[0];
                if (s.getId() == null) {
                    s.setId(seq.incrementAndGet());
                }
                shares.put(s.getId(), s);
                yield s;
            }
            case "findById" -> Optional.ofNullable(shares.get((Long) a[0]));
            case "findByIdAndOwnerId" -> Optional.ofNullable(shares.get((Long) a[0]))
                    .filter(s -> s.getOwnerId().equals(a[1]));
            case "findByOwnerIdOrderByCreatedAtDesc" -> shares.values().stream()
                    .filter(s -> s.getOwnerId().equals(a[0])).toList();
            case "findByTargetUserOrderByCreatedAtDesc" -> shares.values().stream()
                    .filter(s -> s.getTargetUser().equals(a[0])).toList();
            case "findByOwnerIdAndTargetUserAndBookmarkId" -> shares.values().stream()
                    .filter(s -> s.getOwnerId().equals(a[0]) && s.getTargetUser().equals(a[1])
                            && java.util.Objects.equals(s.getBookmarkId(), a[2]))
                    .findFirst();
            case "findByOwnerIdAndTargetUserAndGroupId" -> shares.values().stream()
                    .filter(s -> s.getOwnerId().equals(a[0]) && s.getTargetUser().equals(a[1])
                            && java.util.Objects.equals(s.getGroupId(), a[2]))
                    .findFirst();
            case "findByBookmarkId" -> shares.values().stream()
                    .filter(s -> java.util.Objects.equals(s.getBookmarkId(), a[0])).toList();
            case "findByGroupId" -> shares.values().stream()
                    .filter(s -> java.util.Objects.equals(s.getGroupId(), a[0])).toList();
            case "deleteByBookmarkId" -> {
                shares.values().removeIf(s -> java.util.Objects.equals(s.getBookmarkId(), a[0]));
                yield null;
            }
            case "deleteByGroupId" -> {
                shares.values().removeIf(s -> java.util.Objects.equals(s.getGroupId(), a[0]));
                yield null;
            }
            case "delete" -> {
                shares.remove(((BookmarkShareEntity) a[0]).getId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    // ---------------- user ----------------

    UserRepository users() {
        return proxy(UserRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByUsername" -> Optional.ofNullable(users.get((String) a[0]));
            case "save" -> {
                UserEntity u = (UserEntity) a[0];
                users.put(u.getUsername(), u);
                yield u;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    static UserEntity user(String username, String role) {
        UserEntity u = new UserEntity();
        u.setId(username);
        u.setUsername(username);
        u.setRole(role);
        return u;
    }

    Map<Long, BookmarkEntity> bookmarkMap() { return bookmarks; }

    Set<Long> accountIds() { return accounts.keySet(); }

    /** 便于断言：某收藏的账号 id 列表 */
    List<Long> accountIdsOf(Long bookmarkId) {
        return accounts.values().stream()
                .filter(a -> a.getBookmarkId().equals(bookmarkId))
                .map(BookmarkAccountEntity::getId)
                .sorted()
                .collect(Collectors.toList());
    }
}

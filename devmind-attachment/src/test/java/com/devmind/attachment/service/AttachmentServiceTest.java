package com.devmind.attachment.service;

import com.devmind.attachment.config.AttachmentProperties;
import com.devmind.attachment.dto.AttachmentView;
import com.devmind.attachment.model.AttachmentEntity;
import com.devmind.attachment.repo.AttachmentRepository;
import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-32 AttachmentService 单测（无 Spring 上下文）：repository 用 JDK 动态代理内存 fake。
 * 覆盖：上传落盘 + scope 可见性矩阵（owner/他人/ADMIN × PRIVATE/SHARED）+ scope 切换权限 + 删除清盘。
 */
class AttachmentServiceTest {

    @TempDir
    Path tempDir;

    private FakeRepo repo;
    private AttachmentService service;
    private String actor = "alice";
    private boolean admin;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        AttachmentProperties props = new AttachmentProperties();
        props.setRootDir(tempDir.toString());
        service = new AttachmentService(repo.jpa(), props, fakeIdentity());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private IdentityService fakeIdentity() {
        return new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return actor;
            }

            @Override
            public Optional<UserEntity> currentUser() {
                if (!admin) {
                    return Optional.empty();
                }
                UserEntity u = new UserEntity();
                u.setRole(UserEntity.ROLE_ADMIN);
                return Optional.of(u);
            }
        };
    }

    static class FakeRepo {
        final Map<String, AttachmentEntity> store = new ConcurrentHashMap<>();

        AttachmentRepository jpa() {
            return proxy(AttachmentRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    AttachmentEntity e = (AttachmentEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                case "findAll" -> new ArrayList<>(store.values());
                case "findByUploadedByOrScopeOrderByCreatedAtDesc" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getUploadedBy())
                                || AttachmentEntity.SCOPE_SHARED.equals(e.getScope()))
                        .sorted(Comparator.comparing(AttachmentEntity::getCreatedAt).reversed())
                        .toList();
                case "delete" -> {
                    store.remove(((AttachmentEntity) args[0]).getId());
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private AttachmentView uploadPng(String name) {
        return service.upload(new MockMultipartFile("file", name, "image/png", new byte[]{1, 2, 3}), null);
    }

    @Test
    void uploadPersistsFileAndMetadata() throws Exception {
        AttachmentView v = uploadPng("截图.PNG");
        assertEquals(32, v.attachmentId().length());
        assertEquals(AttachmentEntity.SCOPE_PRIVATE, v.scope());
        assertTrue(v.image());
        AttachmentEntity ent = repo.store.get(v.attachmentId());
        assertEquals(64, ent.getSha256().length());
        // 扩展名小写归一 + 盘文件真实存在
        assertTrue(ent.getStoragePath().endsWith(".png"));
        assertTrue(Files.exists(tempDir.resolve(ent.getStoragePath())));
    }

    @Test
    void privateInvisibleToOtherUsers() {
        AttachmentView v = uploadPng("a.png");
        actor = "bob";
        assertThrows(DevMindException.class, () -> service.raw(v.attachmentId()));
        assertTrue(service.list(null, null, null).isEmpty());
    }

    @Test
    void sharedVisibleToOtherUsers() {
        AttachmentView v = uploadPng("a.png");
        service.updateScope(v.attachmentId(), AttachmentEntity.SCOPE_SHARED);
        actor = "bob";
        assertEquals(1, service.list(null, null, null).size());
        // SHARED 可读但不可改/删
        assertThrows(DevMindException.class,
                () -> service.updateScope(v.attachmentId(), AttachmentEntity.SCOPE_PRIVATE));
        assertThrows(DevMindException.class, () -> service.delete(v.attachmentId()));
    }

    @Test
    void adminSeesAndManagesAll() {
        AttachmentView v = uploadPng("a.png");
        actor = "boss";
        admin = true;
        assertEquals(1, service.list(null, null, null).size());
        service.delete(v.attachmentId());
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void deleteRemovesDiskFile() throws Exception {
        AttachmentView v = uploadPng("a.png");
        Path path = tempDir.resolve(repo.store.get(v.attachmentId()).getStoragePath());
        service.delete(v.attachmentId());
        assertTrue(Files.notExists(path));
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void typeAndKeywordFilter() {
        uploadPng("设计稿.png");
        service.upload(new MockMultipartFile("file", "说明.txt", "text/plain", "hi".getBytes()), null);
        assertEquals(1, service.list(null, null, "image").size());
        assertEquals(1, service.list(null, "设计", null).size());
        assertEquals(2, service.list(null, null, null).size());
    }
}

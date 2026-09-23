package com.devmind.classify.pkg;

import com.devmind.auth.IdentityService;
import com.devmind.classify.config.ClassifyProperties;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.pkg.model.ClassifyPackageEntity;
import com.devmind.classify.pkg.model.ClassifyPackageInstallEntity;
import com.devmind.classify.pkg.repo.ClassifyPackageInstallRepository;
import com.devmind.classify.pkg.repo.ClassifyPackageRepository;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentPkgResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.execution.runner.AgentNodeRouter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-57 FR-03 安装包：上传流式落盘 + sha256 正确性 + 唯一键 409；分发 PENDING→异步收口
 * INSTALLED/FAILED（假 connector 的 future 直接完成）；删除被实例引用时 409。
 */
class ClassifyPackageServiceTest {

    @TempDir
    Path storage;

    private ClassifyPackageRepository repo;
    private ClassifyPackageInstallRepository installRepo;
    private AgentNodeConnector connector;
    private ClassifyPackageService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClassifyPackageRepository.class);
        installRepo = mock(ClassifyPackageInstallRepository.class);
        ClassifyInstanceRepository instanceRepo = mock(ClassifyInstanceRepository.class);
        AgentNodeRouter nodeRouter = mock(AgentNodeRouter.class);
        connector = mock(AgentNodeConnector.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> connectorProvider = mock(ObjectProvider.class);
        when(connectorProvider.getIfAvailable()).thenReturn(connector);
        IdentityService identityService = mock(IdentityService.class);
        when(identityService.currentActor()).thenReturn("tester");
        ClassifyProperties props = new ClassifyProperties();
        props.setStorageDir(storage.toString());
        service = new ClassifyPackageService(repo, installRepo, instanceRepo, props, nodeRouter,
                connectorProvider, identityService);
    }

    private void stubPackageIdentity(long id) {
        when(repo.save(any())).thenAnswer(inv -> {
            ClassifyPackageEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(id);
            }
            return e;
        });
    }

    @Test
    void uploadStoresFileWithCorrectSha() throws Exception {
        stubPackageIdentity(9L);
        when(repo.findByKindAndNameAndPkgVersion("SIDECAR_APP", "laya-sidecar", "1.0"))
                .thenReturn(Optional.empty());
        byte[] content = "fake-zip-content-for-sha".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "laya-sidecar-1.0.zip",
                "application/zip", content);

        var view = service.upload("SIDECAR_APP", "laya-sidecar", "1.0", file);

        assertEquals(9L, view.id());
        String expectSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        assertEquals(expectSha, view.sha256());
        assertEquals(content.length, view.sizeBytes());
        assertEquals("laya-sidecar-1.0.zip", view.originalFilename());
        // 存储按 id 命名且内容一致（流式落盘）
        Path stored = storage.resolve("pkg-9.zip");
        assertTrue(Files.isRegularFile(stored), "存储文件应存在");
        assertEquals(content.length, Files.size(stored));
        // 临时文件不残留
        try (var s = Files.list(storage)) {
            assertEquals(1, s.count(), "只应留下正式文件");
        }
    }

    @Test
    void uploadRejectsDuplicateKindNameVersion() {
        when(repo.findByKindAndNameAndPkgVersion("SIDECAR_APP", "laya", "1.0"))
                .thenReturn(Optional.of(new ClassifyPackageEntity()));
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", null, "x".getBytes());
        var e = assertThrows(DevMindException.class,
                () -> service.upload("SIDECAR_APP", "laya", "1.0", file));
        assertTrue(e.getMessage().contains("同 kind+名称+版本"), e.getMessage());
    }

    @Test
    void uploadRejectsUnknownKind() {
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", null, "x".getBytes());
        var e = assertThrows(DevMindException.class,
                () -> service.upload("WHEELS", "laya", "1.0", file));
        assertTrue(e.getMessage().contains("未知包类型"), e.getMessage());
    }

    @Test
    void installSettlesInstalledFromAck() throws Exception {
        ClassifyPackageEntity pkg = pkgOnDisk(9L);
        when(repo.findById(9L)).thenReturn(Optional.of(pkg));
        when(installRepo.findByPackageIdAndNodeId(9L, "2")).thenReturn(Optional.empty());
        AtomicReference<ClassifyPackageInstallEntity> row = new AtomicReference<>();
        when(installRepo.save(any())).thenAnswer(inv -> {
            ClassifyPackageInstallEntity i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(21L);
            }
            row.set(i);
            return i;
        });
        when(installRepo.findById(21L)).thenAnswer(inv -> Optional.of(row.get()));
        when(connector.pkgInstallAsync(eq("2"), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        AgentPkgResult.ok("/opt/runner/classify/packages/pkg-9")));

        var view = service.install(9L, "2");
        assertEquals("PENDING", view.status());
        assertTrue(view.requestId().startsWith("pkg-9-"), view.requestId());

        awaitStatus(row, "INSTALLED");
        assertEquals("/opt/runner/classify/packages/pkg-9", row.get().getInstallDir());
        verify(connector).pkgInstallAsync(eq("2"), any());
    }

    @Test
    void installSettlesFailedFromAck() throws Exception {
        ClassifyPackageEntity pkg = pkgOnDisk(9L);
        when(repo.findById(9L)).thenReturn(Optional.of(pkg));
        when(installRepo.findByPackageIdAndNodeId(9L, "2")).thenReturn(Optional.empty());
        AtomicReference<ClassifyPackageInstallEntity> row = new AtomicReference<>();
        when(installRepo.save(any())).thenAnswer(inv -> {
            ClassifyPackageInstallEntity i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(22L);
            }
            row.set(i);
            return i;
        });
        when(installRepo.findById(22L)).thenAnswer(inv -> Optional.of(row.get()));
        when(connector.pkgInstallAsync(eq("2"), any()))
                .thenReturn(CompletableFuture.completedFuture(AgentPkgResult.fail("sha256 校验不符")));

        service.install(9L, "2");
        awaitStatus(row, "FAILED");
        assertEquals("sha256 校验不符", row.get().getError());
    }

    @Test
    void deleteBlockedWhenInstanceBindsIt() {
        ClassifyPackageEntity pkg = new ClassifyPackageEntity();
        pkg.setId(9L);
        pkg.setKind("SIDECAR_APP");
        pkg.setName("laya");
        pkg.setPkgVersion("1.0");
        pkg.setStoredPath(storage.resolve("pkg-9.zip").toString());
        when(repo.findById(9L)).thenReturn(Optional.of(pkg));
        ClassifyInstanceRepository instanceRepo = mock(ClassifyInstanceRepository.class);
        var bound = new com.devmind.classify.instance.model.ClassifyInstanceEntity();
        bound.setName("gpu-8377");
        when(instanceRepo.findByAppPackageId(9L)).thenReturn(java.util.List.of(bound));
        ClassifyProperties props = new ClassifyProperties();
        props.setStorageDir(storage.toString());
        ClassifyPackageService svc = new ClassifyPackageService(repo, installRepo, instanceRepo, props,
                mock(AgentNodeRouter.class), mock(ObjectProvider.class), mock(IdentityService.class));

        var e = assertThrows(DevMindException.class, () -> svc.delete(9L));
        assertTrue(e.getMessage().contains("gpu-8377"), e.getMessage());
    }

    // ---------------- helpers ----------------

    private ClassifyPackageEntity pkgOnDisk(long id) throws Exception {
        Path p = storage.resolve("pkg-" + id + ".zip");
        Files.write(p, "zip-bytes".getBytes());
        ClassifyPackageEntity e = new ClassifyPackageEntity();
        e.setId(id);
        e.setKind("SIDECAR_APP");
        e.setName("laya");
        e.setPkgVersion("1.0");
        e.setSha256("a".repeat(64));
        e.setSizeBytes(Files.size(p));
        e.setOriginalFilename("laya.zip");
        e.setStoredPath(p.toString());
        return e;
    }

    private static void awaitStatus(AtomicReference<ClassifyPackageInstallEntity> row, String want)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (row.get() != null && want.equals(row.get().getStatus())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("安装记录未收敛到 " + want + "，当前: "
                + (row.get() == null ? "null" : row.get().getStatus()));
    }
}

package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.test.config.TestAssetProperties;
import com.devmind.test.dto.TestAssetView;
import com.devmind.test.model.TestAssetEntity;
import com.devmind.test.repo.TestAssetRepository;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-104 资产入库回归：ZIP 解包、路径穿越、规模上限、引用保护。 */
class TestAssetImportTest {

    @TempDir
    Path root;

    private TestAssetRepository repo;
    private TestSuiteRepository suiteRepo;
    private TestCaseRepository caseRepo;
    private TestAssetProperties props;
    private TestAssetService service;

    @BeforeEach
    void setUp() {
        repo = mock(TestAssetRepository.class);
        suiteRepo = mock(TestSuiteRepository.class);
        caseRepo = mock(TestCaseRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        IdentityService identityService = mock(IdentityService.class);
        when(identityService.currentActor()).thenReturn("tester");
        props = new TestAssetProperties();
        props.setRootDir(root.toString());
        AtomicLong ids = new AtomicLong();
        when(repo.save(any(TestAssetEntity.class))).thenAnswer(inv -> {
            TestAssetEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.incrementAndGet());
            }
            return e;
        });
        service = new TestAssetService(repo, suiteRepo, caseRepo, projectService, identityService, props,
                mock(AuditService.class));
    }

    private byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    void zipIsExtractedAndCounted() throws IOException {
        TestAssetView view = service.upload("p1",
                file("suite.zip", zip("a.txt", "hello", "sub/b.txt", "world")), "冒烟套件", "note");

        assertEquals(TestAssetEntity.ZIP, view.kind());
        assertEquals(Integer.valueOf(2), view.fileCount());
        assertEquals(1L, view.id().longValue());
        assertEquals(TestAssetEntity.DRAFT, view.reviewStatus());
        Path dir = root.resolve("p1").resolve("1").resolve("files");
        assertEquals("hello", Files.readString(dir.resolve("a.txt")));
        assertEquals("world", Files.readString(dir.resolve("sub/b.txt")));
    }

    @Test
    void singleFileIsStoredAsFileKind() {
        TestAssetView view = service.upload("p1", file("程序.jar", new byte[]{1, 2, 3}), null, null);
        assertEquals(TestAssetEntity.FILE, view.kind());
        assertEquals(Integer.valueOf(1), view.fileCount());
        assertTrue(Files.exists(root.resolve("p1").resolve("1").resolve("source").resolve("程序.jar")));
        assertEquals(64, view.sha256().length());
    }

    @Test
    void zipTraversalRejectedAndPartialFilesCleaned() throws IOException {
        byte[] payload = zip("../evil.txt", "x", "ok.txt", "y");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.upload("p1", file("bad.zip", payload), null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("ZIP"));
        Path dir = root.resolve("p1").resolve("1");
        assertTrue(!Files.exists(dir), "失败上传必须清理解包目录");
        verify(repo).delete(any(TestAssetEntity.class));
    }

    @Test
    void zipFileCountLimitEnforced() throws IOException {
        props.setMaxFiles(1);
        byte[] payload = zip("a.txt", "a", "b.txt", "b");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.upload("p1", file("big.zip", payload), null, null));
        assertTrue(ex.getMessage().contains("文件数超限"));
    }

    @Test
    void zipUncompressedSizeLimitEnforced() throws IOException {
        props.setMaxUncompressedBytes(3);
        byte[] payload = zip("a.txt", "1234");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.upload("p1", file("big.zip", payload), null, null));
        assertTrue(ex.getMessage().contains("解包总量超限"));
    }

    @Test
    void emptyUploadRejected() {
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.upload("p1", file("empty.zip", new byte[0]), null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void referencedAssetCannotBeDeleted() {
        TestAssetEntity e = new TestAssetEntity();
        e.setId(9L);
        e.setProjectId("p1");
        when(repo.findById(9L)).thenReturn(Optional.of(e));
        when(suiteRepo.countBySourceAssetId(9L)).thenReturn(1L);

        DevMindException ex = assertThrows(DevMindException.class, () -> service.delete(9L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不可删除"));
    }

    @Test
    void gitAssetCommitHashValidated() {
        com.devmind.test.dto.TestAssetGitRequest bad = new com.devmind.test.dto.TestAssetGitRequest(
                "回归资产", "https://example.com/repo.git", "main", "not-a-hash", null);
        DevMindException ex = assertThrows(DevMindException.class, () -> service.createGit("p1", bad));
        assertTrue(ex.getMessage().contains("commitHash"));
    }
}

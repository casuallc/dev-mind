package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.config.TestEvidenceProperties;
import com.devmind.test.dto.EvidenceChainVerification;
import com.devmind.test.model.TestEvidenceEntity;
import com.devmind.test.repo.TestEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CAP-104 证据链回归：只追加、sha256、prev_hash 链、篡改可发现、运行删除清理。 */
class EvidenceChainTest {

    @TempDir
    Path root;

    private TestEvidenceRepository repo;
    private TestEvidenceProperties props;
    private EvidenceService service;
    private final List<TestEvidenceEntity> store = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        repo = mock(TestEvidenceRepository.class);
        when(repo.save(any(TestEvidenceEntity.class))).thenAnswer(inv -> {
            TestEvidenceEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.incrementAndGet());
                store.add(e);
            }
            return e;
        });
        when(repo.findByRunIdOrderBySeqNoAsc(anyLong())).thenAnswer(inv -> {
            long runId = inv.getArgument(0);
            return store.stream().filter(e -> e.getRunId() == runId)
                    .sorted(Comparator.comparingInt(TestEvidenceEntity::getSeqNo)).toList();
        });
        when(repo.findTopByRunIdOrderBySeqNoDesc(anyLong())).thenAnswer(inv -> {
            long runId = inv.getArgument(0);
            return store.stream().filter(e -> e.getRunId() == runId)
                    .max(Comparator.comparingInt(TestEvidenceEntity::getSeqNo));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            long runId = inv.getArgument(0);
            store.removeIf(e -> e.getRunId() == runId);
            return null;
        }).when(repo).deleteByRunId(anyLong());
        when(repo.findById(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return store.stream().filter(e -> e.getId() == id).findFirst();
        });
        props = new TestEvidenceProperties();
        props.setRootDir(root.toString());
        service = new EvidenceService(repo, props);
    }

    @Test
    void appendsFormContinuousChain() {
        TestEvidenceEntity first = service.appendText(7L, 1L, "n1", "CASE_LOG", "case.log", "hello");
        TestEvidenceEntity second = service.appendText(7L, 1L, "n1", "CASE_LOG", "assert.json", "{\"ok\":true}");

        assertEquals(Integer.valueOf(1), first.getSeqNo());
        assertEquals("", first.getPrevHash());
        assertEquals(Integer.valueOf(2), second.getSeqNo());
        assertEquals(first.getSha256(), second.getPrevHash());
        assertEquals(EvidenceService.sha256("hello".getBytes(StandardCharsets.UTF_8)), first.getSha256());

        EvidenceChainVerification v = service.verifyChain(7L);
        assertTrue(v.valid());
        assertEquals(2, v.count());
        assertEquals(second.getSha256(), v.headHash());
    }

    @Test
    void tamperedFileIsDetected() throws Exception {
        TestEvidenceEntity e = service.appendText(8L, null, "n1", "QUEUE_LOG", "precheck.json", "content");
        EvidenceChainVerification ok = service.verifyChain(8L);
        assertTrue(ok.valid());

        Files.writeString(root.resolve(e.getStorageRef()), "changed", StandardCharsets.UTF_8);
        EvidenceChainVerification broken = service.verifyChain(8L);
        assertFalse(broken.valid());
        assertTrue(broken.message().contains("文件哈希不匹配"));
    }

    @Test
    void missingFileIsDetected() throws Exception {
        TestEvidenceEntity e = service.appendText(9L, null, "n1", "CASE_LOG", "case.log", "x");
        Files.delete(root.resolve(e.getStorageRef()));
        EvidenceChainVerification broken = service.verifyChain(9L);
        assertFalse(broken.valid());
        assertTrue(broken.message().contains("文件缺失"));
    }

    @Test
    void brokenPrevHashIsDetected() {
        TestEvidenceEntity first = service.appendText(10L, null, "n1", "CASE_LOG", "a.log", "a");
        TestEvidenceEntity second = service.appendText(10L, null, "n1", "CASE_LOG", "b.log", "b");
        second.setPrevHash("deadbeef");

        EvidenceChainVerification broken = service.verifyChain(10L);
        assertFalse(broken.valid());
        assertTrue(broken.message().contains("prev_hash"));
        assertTrue(first.getSha256() != null);
    }

    @Test
    void sequenceGapIsDetected() {
        service.appendText(11L, null, "n1", "CASE_LOG", "a.log", "a");
        TestEvidenceEntity e = service.appendText(11L, null, "n1", "CASE_LOG", "b.log", "b");
        e.setSeqNo(5);
        EvidenceChainVerification broken = service.verifyChain(11L);
        assertFalse(broken.valid());
        assertTrue(broken.message().contains("顺序号不连续"));
    }

    @Test
    void singleFileAndRunSizeLimitsEnforced() {
        props.setMaxFileBytes(2);
        DevMindException tooBig = assertThrows(DevMindException.class,
                () -> service.appendText(12L, null, "n1", "CASE_LOG", "a.log", "abc"));
        assertEquals(ErrorCode.BAD_REQUEST, tooBig.getErrorCode());
        assertTrue(tooBig.getMessage().contains("证据文件超限"));

        props.setMaxFileBytes(1024);
        props.setMaxRunBytes(3);
        service.appendText(13L, null, "n1", "CASE_LOG", "a.log", "ab");
        DevMindException runFull = assertThrows(DevMindException.class,
                () -> service.appendText(13L, null, "n1", "CASE_LOG", "b.log", "cd"));
        assertTrue(runFull.getMessage().contains("证据总量超限"));
    }

    @Test
    void filenameIsSanitized() {
        TestEvidenceEntity e = service.appendText(14L, null, "n1", "CASE_LOG", "../evil.log", "x");
        assertEquals("../evil.log", e.getFilename());
        assertTrue(e.getStorageRef().endsWith("evidence.bin"));
        assertFalse(e.getStorageRef().contains(".."));
        assertTrue(Files.exists(root.resolve(e.getStorageRef())));
    }

    @Test
    void readVerifiesHashOnDownload() throws Exception {
        TestEvidenceEntity e = service.appendText(15L, null, "n1", "CASE_LOG", "a.log", "payload");
        EvidenceService.EvidenceFile file = service.read(e.getId());
        assertEquals("payload", new String(file.content(), StandardCharsets.UTF_8));

        Files.writeString(root.resolve(e.getStorageRef()), "tampered");
        DevMindException ex = assertThrows(DevMindException.class, () -> service.read(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void deleteRunRemovesRowsAndFiles() {
        TestEvidenceEntity e = service.appendText(16L, null, "n1", "CASE_LOG", "a.log", "x");
        assertTrue(Files.exists(root.resolve(e.getStorageRef())));
        service.deleteRun(16L);
        assertFalse(Files.exists(root.resolve("16")));
        assertTrue(service.list(16L).isEmpty());
        assertEquals(Optional.empty(), store.stream().filter(x -> x.getRunId() == 16L).findAny());
    }
}

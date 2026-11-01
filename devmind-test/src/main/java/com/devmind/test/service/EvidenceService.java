package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.config.TestEvidenceProperties;
import com.devmind.test.dto.EvidenceChainVerification;
import com.devmind.test.dto.TestEvidenceView;
import com.devmind.test.model.TestEvidenceEntity;
import com.devmind.test.repo.TestEvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-104 证据服务：只追加 + sha256 + 运行级 prev_hash 链。文件落本地证据目录，
 * 数据库存相对 storageRef；不提供更新/删除 API（运行删除时由 TestRunService 显式清理）。
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final String GENESIS = "";

    private final TestEvidenceRepository repo;
    private final TestEvidenceProperties props;
    private final ConcurrentHashMap<Long, Object> runLocks = new ConcurrentHashMap<>();

    public EvidenceService(TestEvidenceRepository repo, TestEvidenceProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public TestEvidenceEntity appendText(Long runId, Long resultId, String nodeId, String kind,
                                         String filename, String content) {
        return append(runId, resultId, nodeId, kind, filename,
                content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 追加一条证据。单次运行内串行写链（run 级锁），禁止并发破坏 prev_hash。
     */
    public TestEvidenceEntity append(Long runId, Long resultId, String nodeId, String kind,
                                     String filename, byte[] content) {
        return appendInternal(runId, resultId, nodeId, kind, filename, content,
                props.getMaxFileBytes(), props.getMaxRunBytes(), "大文件请走一次性 token 上传端点");
    }

    /** M3 大文件上行：使用独立的大小/总量上限，仍进入同一哈希链。 */
    public TestEvidenceEntity appendLarge(Long runId, Long resultId, String nodeId, String kind,
                                          String filename, byte[] content) {
        return appendInternal(runId, resultId, nodeId, kind, filename, content,
                props.getMaxUploadBytes(), props.getMaxRunUploadBytes(), null);
    }

    private TestEvidenceEntity appendInternal(Long runId, Long resultId, String nodeId, String kind,
                                              String filename, byte[] content,
                                              long fileLimit, long runLimit, String hint) {
        byte[] bytes = content == null ? new byte[0] : content;
        if (bytes.length > fileLimit) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "证据文件超限（" + bytes.length + " > " + fileLimit + " 字节）"
                            + (hint == null ? "" : "：" + hint));
        }
        synchronized (runLocks.computeIfAbsent(runId, k -> new Object())) {
            long used = repo.findByRunIdOrderBySeqNoAsc(runId).stream()
                    .mapToLong(e -> e.getSizeBytes() == null ? 0 : e.getSizeBytes()).sum();
            if (used + bytes.length > runLimit) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "运行证据总量超限（" + runLimit + " 字节）");
            }
            TestEvidenceEntity last = repo.findTopByRunIdOrderBySeqNoDesc(runId).orElse(null);
            int seq = last == null || last.getSeqNo() == null ? 1 : last.getSeqNo() + 1;
            String prev = last == null ? GENESIS : last.getSha256();
            String sha = sha256(bytes);
            String safeName = safeFilename(filename);
            String rel = runId + "/" + seq + "-" + safeName;
            Path target = root().resolve(rel);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException e) {
                throw new DevMindException(ErrorCode.INTERNAL, "证据写入失败: " + e.getMessage());
            }
            TestEvidenceEntity e = new TestEvidenceEntity();
            e.setRunId(runId);
            e.setResultId(resultId);
            e.setNodeId(nodeId);
            e.setKind(kind);
            e.setFilename(filename == null ? safeName : filename);
            e.setSha256(sha);
            e.setPrevHash(prev);
            e.setStorageRef(rel);
            e.setSizeBytes((long) bytes.length);
            e.setSeqNo(seq);
            e.setCreatedAt(Instant.now());
            return repo.save(e);
        }
    }

    public List<TestEvidenceView> list(Long runId) {
        return repo.findByRunIdOrderBySeqNoAsc(runId).stream().map(this::toView).toList();
    }

    public TestEvidenceEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "证据不存在: " + id));
    }

    public EvidenceFile read(Long id) {
        TestEvidenceEntity e = require(id);
        Path file = root().resolve(e.getStorageRef());
        try {
            byte[] bytes = Files.readAllBytes(file);
            String actual = sha256(bytes);
            if (e.getSha256() != null && !e.getSha256().equals(actual)) {
                throw new DevMindException(ErrorCode.CONFLICT, "证据哈希校验失败（文件被改动?）: " + id);
            }
            return new EvidenceFile(e.getFilename(), e.getKind(), bytes);
        } catch (IOException ex) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "证据文件不存在: " + id);
        }
    }

    /** 运行级哈希链校验：seq 连续、prev_hash 指向前一条 sha256。 */
    public EvidenceChainVerification verifyChain(Long runId) {
        List<TestEvidenceEntity> list = repo.findByRunIdOrderBySeqNoAsc(runId);
        String prev = GENESIS;
        int expectedSeq = 1;
        for (TestEvidenceEntity e : list) {
            if (e.getSeqNo() == null || e.getSeqNo() != expectedSeq) {
                return new EvidenceChainVerification(false, list.size(),
                        list.isEmpty() ? null : list.get(list.size() - 1).getSha256(),
                        "证据顺序号不连续：期望 " + expectedSeq + "，实际 " + e.getSeqNo());
            }
            if (!prev.equals(e.getPrevHash() == null ? "" : e.getPrevHash())) {
                return new EvidenceChainVerification(false, list.size(), e.getSha256(),
                        "证据 #" + e.getId() + " prev_hash 不匹配");
            }
            Path file = root().resolve(e.getStorageRef());
            try {
                String actual = sha256(Files.readAllBytes(file));
                if (!actual.equals(e.getSha256())) {
                    return new EvidenceChainVerification(false, list.size(), e.getSha256(),
                            "证据 #" + e.getId() + " 文件哈希不匹配");
                }
            } catch (IOException ex) {
                return new EvidenceChainVerification(false, list.size(), e.getSha256(),
                        "证据 #" + e.getId() + " 文件缺失");
            }
            prev = e.getSha256();
            expectedSeq++;
        }
        return new EvidenceChainVerification(true, list.size(), list.isEmpty() ? null : prev, "哈希链完整");
    }

    /** 运行删除时清理证据（DB 行 + 文件；证据本身无单条删除 API）。 */
    public void deleteRun(Long runId) {
        repo.deleteByRunId(runId);
        deleteFiles(runId);
        runLocks.remove(runId);
    }

    /** 运行删除时清理证据文件（DB 由调用方删）。 */
    public void deleteFiles(Long runId) {
        Path dir = root().resolve(String.valueOf(runId));
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("证据文件删除失败: {}", p);
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("证据目录清理失败: run={} err={}", runId, e.getMessage());
        }
    }

    public TestEvidenceView toView(TestEvidenceEntity e) {
        return new TestEvidenceView(e.getId(), e.getRunId(), e.getResultId(), e.getNodeId(), e.getKind(),
                e.getFilename(), e.getSha256(), e.getPrevHash(), e.getSizeBytes(), e.getSeqNo(), e.getCreatedAt());
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Path root() {
        String dir = props.getRootDir();
        Path base = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("user.dir"), "data", "test-evidence")
                : Path.of(dir);
        return base.toAbsolutePath().normalize();
    }

    private String safeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "evidence.txt" : filename.strip();
        name = name.replace('\\', '_').replace('/', '_');
        if (name.contains("..") || name.length() > 180) {
            name = "evidence.bin";
        }
        return name;
    }

    public record EvidenceFile(String filename, String kind, byte[] content) {
    }
}

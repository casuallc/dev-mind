package com.devmind.test.service;

import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.config.TestEvidenceProperties;
import com.devmind.test.dto.EvidenceTokenRequest;
import com.devmind.test.dto.EvidenceTokenView;
import com.devmind.test.dto.TestEvidenceView;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestEvidenceEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CAP-104 FR-24 大文件证据上行：申请一次性 token（登录）→ 命令侧带 token 上传（免登录）。
 * 服务端校验 token 归属/有效期/一次性、大小、MIME/扩展名与 sha256，落证据表并进入哈希链。
 */
@Service
public class EvidenceUploadService {

    public static final Set<String> ALLOWED_KINDS = Set.of(
            "SCREENSHOT", "LOG", "ATTACHMENT", "ARTIFACT", "STDOUT", "STDERR");

    private static final Set<String> MIME_ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "text/plain", "text/csv", "text/xml", "application/json", "application/xml",
            "application/zip", "application/gzip", "application/x-gzip", "application/x-tar",
            "application/pdf", "application/octet-stream");

    private static final Set<String> EXT_DENIED = Set.of(
            "exe", "dll", "bat", "cmd", "ps1", "sh", "js", "mjs", "html", "htm", "jar", "class");

    private final EvidenceUploadTokenService tokenService;
    private final EvidenceService evidenceService;
    private final TestRunRepository runRepo;
    private final TestCaseResultRepository resultRepo;
    private final TestEvidenceProperties props;
    private final AuditService auditService;
    private final Map<Long, AtomicInteger> uploads = new ConcurrentHashMap<>();

    public EvidenceUploadService(EvidenceUploadTokenService tokenService, EvidenceService evidenceService,
                                 TestRunRepository runRepo, TestCaseResultRepository resultRepo,
                                 TestEvidenceProperties props, AuditService auditService) {
        this.tokenService = tokenService;
        this.evidenceService = evidenceService;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.props = props;
        this.auditService = auditService;
    }

    public EvidenceTokenView issue(Long runId, EvidenceTokenRequest req) {
        TestRunEntity run = requireRun(runId);
        Long resultId = req == null ? null : req.resultId();
        if (resultId != null) {
            TestCaseResultEntity result = resultRepo.findById(resultId)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例结果不存在: " + resultId));
            if (!runId.equals(result.getRunId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "用例结果不属于运行 " + runId);
            }
        }
        String kind = req == null || req.kind() == null || req.kind().isBlank()
                ? "ATTACHMENT" : req.kind().strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不支持的证据类型: " + kind);
        }
        String nodeId = req == null || req.nodeId() == null || req.nodeId().isBlank()
                ? run.getAgentNodeId() : req.nodeId().strip();
        EvidenceUploadTokenService.Issued issued = tokenService.issue(
                run.getProjectId(), runId, resultId, nodeId, kind);
        return new EvidenceTokenView(issued.token(), issued.expiresAt(),
                "/api/test-runs/" + runId + "/evidence", issued.maxBytes(), ALLOWED_KINDS.stream().sorted().toList());
    }

    public TestEvidenceView upload(Long runId, String token, MultipartFile file, String sha256,
                                   String requestNodeId, Long requestResultId) {
        TestRunEntity run = requireRun(runId);
        EvidenceUploadTokenService.TokenInfo info = tokenService.consume(token, runId);
        if (file == null || file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "证据文件为空");
        }
        if (file.getSize() > props.getMaxUploadBytes()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "证据文件超限（" + file.getSize() + " > " + props.getMaxUploadBytes() + " 字节）");
        }
        AtomicInteger counter = uploads.computeIfAbsent(runId, k -> new AtomicInteger());
        if (counter.incrementAndGet() > props.getMaxUploadsPerRun()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "运行证据上传次数超限（" + props.getMaxUploadsPerRun() + " 次）");
        }
        String trimName = file.getOriginalFilename() == null ? "evidence.bin" : file.getOriginalFilename().strip();
        if (trimName.length() > 180) {
            trimName = trimName.substring(trimName.length() - 180);
        }
        if (EXT_DENIED.contains(ext(trimName))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不允许上传该扩展名: " + ext(trimName));
        }
        String mime = file.getContentType() == null ? "application/octet-stream"
                : file.getContentType().strip().toLowerCase(Locale.ROOT);
        String baseMime = mime.contains(";") ? mime.substring(0, mime.indexOf(';')).strip() : mime;
        if (!MIME_ALLOWED.contains(baseMime)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不允许的 MIME 类型: " + mime);
        }
        String kind = info.kind() == null ? "ATTACHMENT" : info.kind();
        if ("SCREENSHOT".equals(kind) && !baseMime.startsWith("image/")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "截图证据必须是图片 (" + mime + ")");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "读取上传文件失败: " + e.getMessage());
        }
        String actual = EvidenceService.sha256(bytes);
        if (sha256 != null && !sha256.isBlank() && !sha256.strip().equalsIgnoreCase(actual)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "sha256 校验失败（声明值与实际内容不一致）");
        }
        Long resultId = info.resultId() != null ? info.resultId() : requestResultId;
        if (resultId != null) {
            TestCaseResultEntity result = resultRepo.findById(resultId)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例结果不存在: " + resultId));
            if (!runId.equals(result.getRunId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "用例结果不属于运行 " + runId);
            }
        }
        String nodeId = info.nodeId();
        if (requestNodeId != null && !requestNodeId.isBlank()
                && !requestNodeId.strip().equals(nodeId)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "上传节点与 token 绑定的节点不一致");
        }
        TestEvidenceEntity evidence = evidenceService.appendLarge(
                runId, resultId, nodeId, kind, trimName, bytes);
        auditService.record("test", "evidence.upload", "node:" + (nodeId == null ? "-" : nodeId),
                run.getProjectId(), true,
                "运行 #" + runId + " 上传证据 #" + evidence.getId() + " (" + bytes.length + " 字节)");
        return evidenceService.toView(evidence);
    }

    private TestRunEntity requireRun(Long runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试运行不存在: " + runId));
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

package com.devmind.test.controller;

import com.devmind.test.dto.EvidenceChainVerification;
import com.devmind.test.dto.EvidenceTokenRequest;
import com.devmind.test.dto.EvidenceTokenView;
import com.devmind.test.dto.TestEvidenceView;
import com.devmind.test.service.EvidenceExportService;
import com.devmind.test.service.EvidenceService;
import com.devmind.test.service.EvidenceUploadService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CAP-104 FR-08 证据：只读列表/下载 + 哈希链校验；不提供更新/删除 API。
 */
@RestController
@RequestMapping("/api")
public class TestEvidenceController {

    private final EvidenceService service;
    private final EvidenceExportService exportService;
    private final EvidenceUploadService uploadService;

    public TestEvidenceController(EvidenceService service, EvidenceExportService exportService,
                                  EvidenceUploadService uploadService) {
        this.service = service;
        this.exportService = exportService;
        this.uploadService = uploadService;
    }

    @GetMapping("/test-runs/{runId}/evidence")
    public List<TestEvidenceView> list(@PathVariable Long runId) {
        return service.list(runId);
    }

    @GetMapping("/test-runs/{runId}/evidence/verify")
    public EvidenceChainVerification verify(@PathVariable Long runId) {
        return service.verifyChain(runId);
    }

    /** FR-24 申请一次性大文件上传 token（登录态）。 */
    @PostMapping("/test-runs/{runId}/evidence-token")
    public EvidenceTokenView token(@PathVariable Long runId, @RequestBody(required = false) EvidenceTokenRequest req) {
        return uploadService.issue(runId, req);
    }

    /** FR-24 大文件上传（token 自证，免登录；单次使用，校验大小/MIME/sha256）。 */
    @PostMapping("/test-runs/{runId}/evidence")
    public TestEvidenceView upload(@PathVariable Long runId,
                                   @RequestHeader(value = "X-Evidence-Token", required = false) String token,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam(required = false) String sha256,
                                   @RequestParam(required = false) String nodeId,
                                   @RequestParam(required = false) Long resultId) {
        return uploadService.upload(runId, token, file, sha256, nodeId, resultId);
    }

    /** FR-15 单运行证据包：manifest + 报告 + 日志 + 断言 + 证据文件。 */
    @GetMapping("/test-runs/{runId}/evidence.zip")
    public ResponseEntity<byte[]> exportRun(@PathVariable Long runId) {
        return zip("test-run-" + runId + "-evidence.zip", exportService.exportRun(runId));
    }

    /** FR-15 方案运行证据包：父汇总 + 每个子运行目录 + 全量证据。 */
    @GetMapping("/test-plan-runs/{id}/evidence.zip")
    public ResponseEntity<byte[]> exportPlanRun(@PathVariable Long id) {
        return zip("test-plan-run-" + id + "-evidence.zip", exportService.exportPlanRun(id));
    }

    @GetMapping("/test-evidence/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        EvidenceService.EvidenceFile file = service.read(id);
        MediaType type = isText(file.kind())
                ? new MediaType("text", "plain", StandardCharsets.UTF_8)
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename() == null ? "evidence.bin" : file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }

    private ResponseEntity<byte[]> zip(String filename, byte[] content) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content);
    }

    private boolean isText(String kind) {
        return "CONSOLE_LOG".equals(kind) || "QUEUE_LOG".equals(kind) || "CLEANUP_LOG".equals(kind)
                || "STDOUT".equals(kind) || "STDERR".equals(kind) || "RESULT_JSON".equals(kind)
                || "JUNIT_XML".equals(kind) || "MANUAL_REVIEW".equals(kind);
    }
}

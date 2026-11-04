package com.devmind.security.service;

import com.devmind.common.attachment.AttachmentStore;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.SecurityScanReportEntity;
import com.devmind.security.repo.SecurityScanReportRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 报告上行落库（CAP-66 FR-03）：token 校验在 {@link SecurityScanService#validateUploadToken}，
 * 本类负责格式/大小白名单 → 附件 PRIVATE 落盘 → report 行 uk(scan,engine,format) 幂等覆盖
 * （重传删旧附件换新，不堆历史）。
 */
@Service
public class SecurityReportService {

    /** 上行格式白名单（deptree=SCA 引入链的 mvn dependency:tree 文本） */
    private static final Set<String> FORMATS = Set.of(
            SecurityScanReportEntity.FORMAT_JSON, SecurityScanReportEntity.FORMAT_HTML,
            SecurityScanReportEntity.FORMAT_SARIF, SecurityScanReportEntity.FORMAT_DEPTREE);

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            SecurityScanReportEntity.FORMAT_JSON, "application/json",
            SecurityScanReportEntity.FORMAT_HTML, "text/html",
            SecurityScanReportEntity.FORMAT_SARIF, "application/sarif+json",
            SecurityScanReportEntity.FORMAT_DEPTREE, "text/plain");

    private final SecurityScanReportRepository reportRepo;
    private final ObjectProvider<AttachmentStore> attachmentStore;
    private final SecurityProperties props;

    public SecurityReportService(SecurityScanReportRepository reportRepo,
                                 ObjectProvider<AttachmentStore> attachmentStore,
                                 SecurityProperties props) {
        this.reportRepo = reportRepo;
        this.attachmentStore = attachmentStore;
        this.props = props;
    }

    /**
     * 接收一份引擎报告。
     *
     * @param tokenEngine 上传 token 绑定的引擎（与表单 engine 必须一致，防 token 串引擎复用）
     */
    public SecurityScanReportEntity accept(long scanId, String tokenEngine, String engine,
                                           String format, String filename, byte[] content) {
        if (engine == null || !engine.equals(tokenEngine)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "engine 与上传 token 绑定不一致（token 绑 " + tokenEngine + "）");
        }
        if (format == null || !FORMATS.contains(format)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "format 非法（可选 json/html/sarif/deptree）: " + format);
        }
        if (content == null || content.length == 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "报告内容为空");
        }
        if (content.length > props.getUploadMaxBytes()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "报告超过大小上限 " + props.getUploadMaxBytes() + " 字节");
        }
        AttachmentStore store = attachmentStore.getIfAvailable();
        if (store == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "attachment 模块未装配，无法接收扫描报告");
        }
        String safeName = (filename == null || filename.isBlank() ? engine + "-report." + format : filename)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        String attachmentId = store.store(new ByteArrayInputStream(content), content.length, safeName,
                CONTENT_TYPES.getOrDefault(format, "application/octet-stream"),
                AttachmentStore.SCOPE_PRIVATE, SecurityScanService.REPORT_UPLOADER,
                "security-scan", "安全扫描 #" + scanId + " " + engine + " " + format + " 原始报告");

        SecurityScanReportEntity row = reportRepo
                .findByScanIdAndEngineAndFormat(scanId, engine, format)
                .orElse(null);
        if (row != null) {
            // uk 幂等覆盖：重传删旧附件换新（对齐 CAP-83 产出同名覆盖先例）
            store.delete(row.getAttachmentId());
            row.setAttachmentId(attachmentId);
            row.setCreatedAt(Instant.now());
        } else {
            row = new SecurityScanReportEntity();
            row.setScanId(scanId);
            row.setEngine(engine);
            row.setFormat(format);
            row.setAttachmentId(attachmentId);
            row.setCreatedAt(Instant.now());
        }
        return reportRepo.save(row);
    }
}

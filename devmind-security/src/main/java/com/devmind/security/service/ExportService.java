package com.devmind.security.service;

import com.devmind.common.attachment.AttachmentStore;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecurityScanEntity;
import com.devmind.security.model.SecurityScanReportEntity;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.security.repo.SecurityScanReportRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 导出（CAP-66 FR-07）：CSV（全量 finding 含降权/抑制行）与合并 SARIF（各引擎原始
 * SARIF 报告的 runs 合并为一个文件，供 GitHub Code Scanning 等外部平台消费）。
 * 产物进 CAP-32 附件（SHARED 全员可读），与 PRIVATE 的原始报告分级。
 */
@Service
public class ExportService {

    private final SecurityFindingRepository findingRepo;
    private final SecurityScanReportRepository reportRepo;
    private final SecurityScanService scanService;
    private final ObjectProvider<AttachmentStore> attachmentStore;
    private final ObjectMapper mapper;

    public ExportService(SecurityFindingRepository findingRepo,
                         SecurityScanReportRepository reportRepo,
                         SecurityScanService scanService,
                         ObjectProvider<AttachmentStore> attachmentStore,
                         ObjectMapper mapper) {
        this.findingRepo = findingRepo;
        this.reportRepo = reportRepo;
        this.scanService = scanService;
        this.attachmentStore = attachmentStore;
        this.mapper = mapper;
    }

    public record ExportResult(String attachmentId, String fileName, int count) {
    }

    /** CSV 导出（UTF-8 带 BOM，Excel 直开不乱码）；返回附件 id */
    public ExportResult exportCsv(SecurityScanEntity scan, String actor) {
        List<SecurityFindingEntity> findings = findingRepo.findByScanId(scan.getId());
        StringBuilder csv = new StringBuilder("﻿"); // BOM
        csv.append("engine,severity,status,rule_id,title,file_path,line_no,cve,group_id,artifact_id,")
                .append("dep_version,dep_scope,direct_dep,dep_path,epss_score,kev,")
                .append("remediation_action,recommended_version,fingerprint\n");
        for (SecurityFindingEntity f : findings) {
            Map<String, Object> detail = readJson(f.getDetailJson());
            Map<String, Object> remediation = detail.get("remediation") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            csv.append(row(
                    f.getEngine(), f.getSeverity(), f.getStatus(), f.getRuleId(), f.getTitle(),
                    f.getFilePath(), str(f.getLineNo()), str(detail.get("cve")),
                    str(detail.get("groupId")), str(detail.get("artifactId")), str(detail.get("depVersion")),
                    f.getDepScope(), f.getDirectDep() == null ? "" : String.valueOf(f.getDirectDep()),
                    str(detail.get("depPath")), str(detail.get("epssScore")),
                    Boolean.TRUE.equals(detail.get("kev")) ? "true" : "",
                    str(remediation.get("action")), str(remediation.get("recommendedVersion")),
                    f.getFingerprint()));
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = "security-scan-" + scan.getId() + ".csv";
        String attachmentId = store().store(new ByteArrayInputStream(bytes), bytes.length, fileName,
                "text/csv", AttachmentStore.SCOPE_SHARED, actor,
                "security-scan", "安全扫描 #" + scan.getId() + " CSV 导出");
        return new ExportResult(attachmentId, fileName, findings.size());
    }

    /** 合并 SARIF：各引擎原始 SARIF 报告 runs 合并；无 SARIF 报告 → 400 */
    public ExportResult exportSarif(SecurityScanEntity scan, String actor) {
        List<SecurityScanReportEntity> sarifs = reportRepo.findByScanId(scan.getId()).stream()
                .filter(r -> SecurityScanReportEntity.FORMAT_SARIF.equals(r.getFormat()))
                .toList();
        if (sarifs.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "本次扫描无 SARIF 报告（仅勾选 SCA 引擎？），无法导出合并 SARIF");
        }
        ObjectNode merged = mapper.createObjectNode();
        merged.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        merged.put("version", "2.1.0");
        ArrayNode runs = merged.putArray("runs");
        for (SecurityScanReportEntity r : sarifs) {
            try {
                byte[] bytes = scanService.resolveAttachment(r.getAttachmentId());
                JsonNode root = mapper.readTree(bytes);
                for (JsonNode run : root.path("runs")) {
                    runs.add(run);
                }
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "SARIF 报告（" + r.getEngine() + "）解析失败: " + e.getMessage());
            }
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged)
                .getBytes(StandardCharsets.UTF_8);
        String fileName = "security-scan-" + scan.getId() + ".sarif";
        String attachmentId = store().store(new ByteArrayInputStream(bytes), bytes.length, fileName,
                "application/sarif+json", AttachmentStore.SCOPE_SHARED, actor,
                "security-scan", "安全扫描 #" + scan.getId() + " 合并 SARIF 导出");
        return new ExportResult(attachmentId, fileName, runs.size());
    }

    private AttachmentStore store() {
        AttachmentStore s = attachmentStore.getIfAvailable();
        if (s == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "attachment 模块未装配，无法导出");
        }
        return s;
    }

    private static String row(String... cols) {
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(escape(c));
        }
        return sb.append('\n').toString();
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}

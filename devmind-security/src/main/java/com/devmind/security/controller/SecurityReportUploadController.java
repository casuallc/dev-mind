package com.devmind.security.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.SecurityScanReportEntity;
import com.devmind.security.model.SecurityUploadTokenEntity;
import com.devmind.security.service.SecurityReportService;
import com.devmind.security.service.SecurityScanService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/**
 * 报告上行端点（CAP-66 FR-03）：runner 侧脚本 curl -F 回传原始报告。
 * 不走登录鉴权（SecurityConfig permitAll），仅凭 X-Scan-Token 一次性凭据
 * （SHA-256 落库、绑 scanId+engine+TTL）；格式/扩展名/大小白名单在服务层卡死。
 */
@RestController
@RequestMapping("/api/security/scans")
public class SecurityReportUploadController {

    /** 扩展名白名单（防借报告通道上传可执行物进附件库） */
    private static final Set<String> EXT_WHITELIST = Set.of("json", "html", "sarif", "txt", "xml");

    private final SecurityScanService scanService;
    private final SecurityReportService reportService;

    public SecurityReportUploadController(SecurityScanService scanService,
                                          SecurityReportService reportService) {
        this.scanService = scanService;
        this.reportService = reportService;
    }

    @PostMapping("/{id}/reports")
    public Map<String, Object> upload(@PathVariable long id,
                                      @RequestHeader("X-Scan-Token") String token,
                                      @RequestParam String engine,
                                      @RequestParam String format,
                                      @RequestParam("file") MultipartFile file) {
        SecurityUploadTokenEntity validated = scanService.validateUploadToken(token, id);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!EXT_WHITELIST.contains(ext)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "报告扩展名非法（允许 json/html/sarif/txt/xml）: " + ext);
        }
        try {
            SecurityScanReportEntity row = reportService.accept(id, validated.getEngine(), engine,
                    format, filename, file.getBytes());
            return Map.of("ok", true, "reportId", row.getId());
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "报告读取失败: " + e.getMessage());
        }
    }
}

package com.devmind.agent.controller;

import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.service.FileTransferStore;
import com.devmind.agent.service.FileTransferStore.Transfer;
import com.devmind.common.agent.AgentFileRequest;
import com.devmind.common.agent.AgentFileResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAP-65 节点文件浏览器 REST（/api/agent-nodes/{id}/files，SecurityConfig 全端点 ADMIN——
 * GET 也敏感：可读节点白名单内任意文件）。
 *
 * <p>list/read/write/rename/delete 走 WS file 帧（小 JSON 请求应答）；upload/download 走
 * HTTP 中转（本端点暂存/落盘 + runner 主动拉推 + file_ack 收口）。节点离线/runner 协议
 * &lt;v18/白名单未配置均由 registry 抛 409。</p>
 */
@RestController
@RequestMapping("/api/agent-nodes/{id}/files")
public class AgentNodeFilesController {

    /** 单文件中转上限（100MB） */
    static final long TRANSFER_CAP = 100L * 1024 * 1024;

    private final AgentNodeService nodeService;
    private final AgentConnectionRegistry registry;
    private final FileTransferStore transfers;

    public AgentNodeFilesController(AgentNodeService nodeService, AgentConnectionRegistry registry,
                                    FileTransferStore transfers) {
        this.nodeService = nodeService;
        this.registry = registry;
        this.transfers = transfers;
    }

    public record WriteRequest(String root, String path, String content) {
    }

    public record RenameRequest(String root, String path, String newName) {
    }

    /** recursive 用包装类型（Jackson 3：primitive 布尔遇 null 直接抛错；null 按 false 对待）。 */
    public record DeleteRequest(String root, String path, Boolean recursive) {
    }

    /** 目录列表：payload = {entries:[{name,dir,size,mtime}], truncated}。 */
    @GetMapping("/list")
    public Map<String, Object> list(@PathVariable Long id, @RequestParam String root,
                                    @RequestParam(defaultValue = "") String path) {
        return unwrap(registry.file(nodeId(id), AgentFileRequest.list(root, path)));
    }

    /** 文本读取：payload = {content,size}。 */
    @GetMapping("/read")
    public Map<String, Object> read(@PathVariable Long id, @RequestParam String root,
                                    @RequestParam String path) {
        return unwrap(registry.file(nodeId(id), AgentFileRequest.read(root, path)));
    }

    /** 文本保存（≤512KB，服务端预检在 registry）。 */
    @PutMapping("/content")
    public Map<String, Object> write(@PathVariable Long id, @RequestBody WriteRequest req) {
        return unwrap(registry.file(nodeId(id),
                AgentFileRequest.write(req.root(), req.path(), req.content())));
    }

    @PostMapping("/rename")
    public Map<String, Object> rename(@PathVariable Long id, @RequestBody RenameRequest req) {
        if (req.newName() == null || req.newName().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "newName 不能为空");
        }
        return unwrap(registry.file(nodeId(id),
                AgentFileRequest.rename(req.root(), req.path(), req.newName())));
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@PathVariable Long id, @RequestBody DeleteRequest req) {
        return unwrap(registry.file(nodeId(id),
                AgentFileRequest.delete(req.root(), req.path(),
                        req.recursive() != null && req.recursive())));
    }

    /**
     * 上传（multipart）：浏览器字节先落中转临时文件（流式 + sha256）→ file{op=upload,
     * transferId,size,sha256} → runner 主动 HTTP 拉取校验后原子写 → ack 收口 → 删临时文件。
     * >100MB 直接 409 不暂存。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable Long id, @RequestParam String root,
                                      @RequestParam(defaultValue = "") String path,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        String nodeId = nodeId(id);
        if (file.getSize() > TRANSFER_CAP) {
            throw new DevMindException(ErrorCode.CONFLICT, "上传文件超限（>100MB）: " + file.getOriginalFilename());
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String rel = joinRel(path, fileName);
        Transfer t = transfers.registerUpload(nodeId, fileName, 0, null);
        try {
            MessageDigest md = newDigest();
            long size;
            try (InputStream in = file.getInputStream();
                 DigestInputStream din = new DigestInputStream(in, md);
                 OutputStream out = Files.newOutputStream(t.file())) {
                size = din.transferTo(out);
            }
            String sha256 = HexFormat.of().formatHex(md.digest());
            transfers.markReceived(t.id(), size, sha256);
            Map<String, Object> payload = unwrap(registry.file(nodeId,
                    AgentFileRequest.upload(root, rel, t.id(), size, sha256)));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("name", fileName);
            resp.put("path", rel);
            resp.put("size", size);
            resp.putAll(payload);
            return resp;
        } finally {
            transfers.complete(t.id()); // 用后即删（runner 已拉走或已失败）
        }
    }

    /**
     * 下载：登记中转 → file{op=download, transferId} → runner 主动 HTTP 推字节（ack 到达时
     * 临时文件已收流完毕且 sha 校验过）→ 从临时文件流式回浏览器（RFC5987 文件名，中文不乱码）
     * → 删临时文件。
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id,
                                                          @RequestParam String root,
                                                          @RequestParam String path) {
        String nodeId = nodeId(id);
        String fileName = lastSegment(path);
        Transfer t = transfers.registerDownload(nodeId, fileName);
        try {
            unwrap(registry.file(nodeId, AgentFileRequest.download(root, path, t.id())));
        } catch (RuntimeException e) {
            transfers.complete(t.id());
            throw e;
        }
        Transfer received = transfers.get(t.id()).orElse(t);
        long size = received.size() != null ? received.size() : -1;
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(t.file())) {
                in.transferTo(out);
            } finally {
                transfers.complete(t.id()); // 用后即删
            }
        };
        var resp = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName));
        if (size >= 0) {
            resp = resp.contentLength(size);
        }
        return resp.body(body);
    }

    /** runner 业务失败（ok=false）透传为 409 文案（terminal_exec 先例）。 */
    private static Map<String, Object> unwrap(AgentFileResult r) {
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    r.error() == null || r.error().isBlank() ? "文件操作失败" : r.error());
        }
        return r.payload() == null ? Map.of() : r.payload();
    }

    private String nodeId(Long id) {
        return String.valueOf(nodeService.require(id).getId()); // 节点不存在 → 404
    }

    /** multipart 文件名消毒：剥掉浏览器可能带的路径前缀，拒绝分隔符/逃逸名。 */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original.strip();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法文件名: " + original);
        }
        return name;
    }

    /** 目标相对路径 = 目录 + 文件名（path 空 = 根目录）。 */
    private static String joinRel(String dir, String fileName) {
        String d = dir == null ? "" : dir.strip();
        return d.isEmpty() ? fileName : d + "/" + fileName;
    }

    private static String lastSegment(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1);
        return name.isEmpty() ? "download" : name;
    }

    /** RFC5987 Content-Disposition：中文文件名走 filename* 百分号编码，不乱码。 */
    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.service.FileTransferStore;
import com.devmind.agent.service.FileTransferStore.Transfer;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAP-65 文件中转端点：大文件（≤100MB）字节不经 WS，runner 凭 transferId 主动拉/推
 * （CAP-34 上下文包 / CAP-57 模型包同模式）。
 *
 * <ul>
 *   <li>GET：upload 场景——runner 拉取浏览器已暂存的字节；</li>
 *   <li>POST：download 场景——runner 推送节点文件字节（octet-stream + X-SHA256 头），
 *       服务端流式落临时文件并校验 sha256。</li>
 * </ul>
 *
 * <p>SecurityConfig 对 /api/agent/files-transfer/** permitAll，此处在控制器内做节点 token
 * 认证（{@link AgentOutputController} 先例），且 token 解析出的节点必须与中转登记的节点一致。</p>
 */
@RestController
@RequestMapping("/api/agent/files-transfer")
public class AgentFileTransferController {

    /** 单文件中转上限（100MB） */
    static final long TRANSFER_CAP = 100L * 1024 * 1024;

    private final AgentNodeService nodeService;
    private final FileTransferStore transfers;

    public AgentFileTransferController(AgentNodeService nodeService, FileTransferStore transfers) {
        this.nodeService = nodeService;
        this.transfers = transfers;
    }

    /** upload：runner 拉取暂存字节（用后即删由 REST 链路 ack 收口 + GC 兜底，此处只读）。 */
    @GetMapping("/{transferId}")
    public ResponseEntity<Resource> pull(@PathVariable String transferId,
                                         @RequestParam(required = false) String token) {
        Transfer t = authorize(transferId, token, true);
        FileSystemResource res = new FileSystemResource(t.file());
        if (!res.exists()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "中转文件不存在或已过期: " + transferId);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(t.size())
                .body(res);
    }

    /** download：runner 推字节，服务端流式落盘 + sha256 校验（100MB 上限，超限即断）。 */
    @PostMapping("/{transferId}")
    public Map<String, Object> push(@PathVariable String transferId,
                                    @RequestParam(required = false) String token,
                                    @RequestHeader(value = "X-SHA256", required = false) String sha256,
                                    HttpServletRequest req) throws IOException {
        Transfer t = authorize(transferId, token, false);
        MessageDigest md = newDigest();
        long size;
        try (InputStream in = req.getInputStream();
             DigestInputStream din = new DigestInputStream(in, md);
             OutputStream out = Files.newOutputStream(t.file())) {
            size = copyCapped(din, out);
        } catch (IOException | RuntimeException e) {
            transfers.complete(transferId); // 收流失败：删半截文件，等待侧以 ack 超时/断连收口
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (sha256 != null && !sha256.isBlank() && !sha256.equalsIgnoreCase(actual)) {
            transfers.complete(transferId);
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "中转文件 sha256 不一致（runner 声明 " + sha256 + "，实收 " + actual + "）");
        }
        transfers.markReceived(transferId, size, actual);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("size", size);
        resp.put("sha256", actual);
        return resp;
    }

    /** token → 节点 → 中转登记三段鉴权：节点有效、中转存在、类型与归属匹配。 */
    private Transfer authorize(String transferId, String token, boolean upload) {
        var node = nodeService.resolveByToken(token)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "文件中转需要有效节点 token"));
        Transfer t = transfers.get(transferId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "中转不存在或已过期: " + transferId));
        if (t.upload() != upload) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "中转类型不匹配: " + transferId);
        }
        if (!t.nodeId().equals(String.valueOf(node.getId()))) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "节点 token 与中转归属不匹配");
        }
        return t;
    }

    /** 流式拷贝，超过 100MB 立即中断（防恶意/异常推送打爆磁盘）。 */
    private static long copyCapped(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > TRANSFER_CAP) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "中转文件超限（>100MB）");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

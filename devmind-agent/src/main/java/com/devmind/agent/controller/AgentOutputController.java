package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.SessionOutputSink;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-37 FR-01 会话产出上传端点：runner 在会话进程退出前把工作区 .devmind/output/ 下的
 * 结构化产出（analysis.md / design.md / wi-plan.json）同步 POST 上来，落 session_outputs 表；
 * 响应返回后 runner 才发 exit 帧，保证 session.completed 监听器读取无竞态。
 *
 * <p>SecurityConfig 对 POST /api/agent/output/** permitAll，此处在控制器内做节点 token 认证
 * （与 {@link AgentContextController} 同通道先例）。</p>
 */
@RestController
@RequestMapping("/api/agent/output")
public class AgentOutputController {

    /** 单文件内容上限（字符数，约 1MB UTF-8） */
    static final int MAX_FILE_CHARS = 1_048_576;
    /** 单次上传文件数上限 */
    static final int MAX_FILES = 16;
    /** 文件名白名单（纯文件名，防路径逃逸） */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final AgentNodeService nodeService;
    private final ObjectProvider<SessionOutputSink> outputSink;

    public AgentOutputController(AgentNodeService nodeService,
                                 ObjectProvider<SessionOutputSink> outputSink) {
        this.nodeService = nodeService;
        this.outputSink = outputSink;
    }

    public record FileItem(String name, String content) {
    }

    public record OutputUploadRequest(List<FileItem> files) {
    }

    @PostMapping("/{sessionId}")
    public void upload(@PathVariable String sessionId,
                       @RequestParam(required = false) String token,
                       @RequestBody OutputUploadRequest req) {
        if (nodeService.resolveByToken(token).isEmpty()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "上传会话产出需要有效节点 token");
        }
        SessionOutputSink sink = outputSink.getIfAvailable();
        if (sink == null) {
            throw new DevMindException(ErrorCode.INTERNAL, "产出存储服务未装配");
        }
        List<FileItem> files = req.files() == null ? List.of() : req.files();
        if (files.size() > MAX_FILES) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "单次上传文件数超限（" + files.size() + " > " + MAX_FILES + "）");
        }
        for (FileItem f : files) {
            if (f.name() == null || !NAME_PATTERN.matcher(f.name()).matches()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "非法产出文件名: " + f.name());
            }
            if (f.content() != null && f.content().length() > MAX_FILE_CHARS) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "产出文件过大: " + f.name() + "（" + f.content().length() + " 字符）");
            }
        }
        sink.store(sessionId, files.stream()
                .map(f -> new SessionOutputSink.OutputFile(f.name(), f.content()))
                .toList());
    }
}

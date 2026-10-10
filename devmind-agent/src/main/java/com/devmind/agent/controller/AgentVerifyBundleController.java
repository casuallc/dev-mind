package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.verify.VerifyBundleStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

/**
 * CAP-73 验证产物包上下行端点：构建节点 runner 打 zip 后凭节点 token POST 上传，
 * 环境节点 runner 凭自身节点 token GET 拉取（都是 HTTP 大文件通道，不进 WS 帧）。
 *
 * <p>与 CAP-56 执行包端点（{@link AgentLabBundleController}）同构：SecurityConfig 对该前缀
 * permitAll，节点 token 在控制器内判定；存取本体走 common 的 {@link VerifyBundleStore} SPI
 * （flow 模块实现），agent 不反向依赖 flow。run↔节点绑定校验在 SPI 实现内完成
 * （上传者须为该 run 的构建节点、下载者须为环境节点）。</p>
 */
@RestController
@RequestMapping("/api/agent/verify/runs")
public class AgentVerifyBundleController {

    private final AgentNodeService nodeService;
    private final ObjectProvider<VerifyBundleStore> storeProvider;

    public AgentVerifyBundleController(AgentNodeService nodeService,
                                       ObjectProvider<VerifyBundleStore> storeProvider) {
        this.nodeService = nodeService;
        this.storeProvider = storeProvider;
    }

    /** 构建节点上传产物包（application/octet-stream 流式；超限/阶段不符由 SPI 抛 409） */
    @PostMapping("/{runId}/bundle")
    public void upload(@PathVariable long runId, @RequestParam(required = false) String token,
                       HttpServletRequest request) throws Exception {
        var node = nodeService.resolveByToken(token)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "上传验证产物包需要有效节点 token"));
        store().storeBundle(runId, String.valueOf(node.getId()), request.getInputStream());
    }

    /** 环境节点拉取产物包（流式下载；包不存在/未上传由 SPI 抛 404） */
    @GetMapping("/{runId}/bundle")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long runId,
                                                          @RequestParam(required = false) String token) {
        var node = nodeService.resolveByToken(token)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "拉取验证产物包需要有效节点 token"));
        InputStream in = store().openBundle(runId, String.valueOf(node.getId()));
        StreamingResponseBody body = out -> {
            try (in) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"verify-bundle-" + runId + ".zip\"")
                .body(body);
    }

    private VerifyBundleStore store() {
        VerifyBundleStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "验证链未装配（flow 模块缺席），无法存取产物包");
        }
        return store;
    }
}

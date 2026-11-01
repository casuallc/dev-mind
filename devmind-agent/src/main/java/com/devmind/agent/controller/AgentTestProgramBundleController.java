package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.program.TestProgramBundleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-104 M1.5 程序包下发：runner 凭节点 token 拉取已评审的程序 ZIP。
 * SecurityConfig 对该前缀 permitAll，token 在控制器内校验；存取走 common SPI，
 * agent 不反向依赖 devmind-test。包缺失/未评审/被篡改统一 404，避免泄露资产状态。
 */
@RestController
@RequestMapping("/api/agent/test-program")
public class AgentTestProgramBundleController {

    private final AgentNodeService nodeService;
    private final ObjectProvider<TestProgramBundleProvider> provider;

    public AgentTestProgramBundleController(AgentNodeService nodeService,
                                            ObjectProvider<TestProgramBundleProvider> provider) {
        this.nodeService = nodeService;
        this.provider = provider;
    }

    @GetMapping("/assets/{assetId}/bundle")
    public ResponseEntity<byte[]> bundle(@PathVariable long assetId,
                                         @RequestParam(required = false) String token) {
        nodeService.resolveByToken(token)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "拉取测试程序包需要有效节点 token"));
        TestProgramBundleProvider p = provider.getIfAvailable();
        if (p == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "测试模块未装配，无法下发程序包");
        }
        TestProgramBundleProvider.Bundle bundle = p.packageBytes(assetId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "程序包不存在、未评审或校验失败: " + assetId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + bundle.filename() + "\"")
                .header("X-Content-Sha256", bundle.sha256())
                .contentLength(bundle.bytes().length)
                .body(bundle.bytes());
    }
}

package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.decision.LabBundle;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import java.util.List;
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
 * CAP-56 执行包拉取端点：runner 收 exec 帧 {@code bundle{kind,id}} 后凭节点 token 走 HTTP 拉取
 * 评测/微调的执行包（脚本 + 数据），物化到临时目录再跑命令。
 *
 * <p>与 CAP-34 上下文包端点（{@link AgentContextController}）同构：SecurityConfig 对该前缀
 * permitAll，节点 token 在控制器内判定；内容由 common 的 {@link DecisionLabBundleProvider} SPI
 * 供给，agent 模块不反向依赖决策实验室。</p>
 *
 * <p><b>为什么走 HTTP 而不是塞进 exec 帧</b>：帧是 WS 上的 JSON，数据集几百 KB 会把每帧编解码
 * 拖慢，而"文件"这件事帧里本来就表达不了。拉不到 = 该步骤失败（runner 侧返回非零退出码并说明
 * 原因），不降级。</p>
 */
@RestController
@RequestMapping("/api/agent/decision-lab/bundles")
public class AgentLabBundleController {

    private static final List<String> KINDS =
            List.of(DecisionLabBundleProvider.KIND_EVALUATION, DecisionLabBundleProvider.KIND_FINETUNE);

    private final AgentNodeService nodeService;
    private final ObjectProvider<DecisionLabBundleProvider> bundleProvider;

    public AgentLabBundleController(AgentNodeService nodeService,
                                    ObjectProvider<DecisionLabBundleProvider> bundleProvider) {
        this.nodeService = nodeService;
        this.bundleProvider = bundleProvider;
    }

    @GetMapping("/{kind}/{id}")
    public ResponseEntity<byte[]> pull(@PathVariable String kind, @PathVariable String id,
                                       @RequestParam(required = false) String token) {
        if (nodeService.resolveByToken(token).isEmpty()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "拉取执行包需要有效节点 token");
        }
        if (!KINDS.contains(kind)) {
            // 不认识的 kind 直接说清有哪几种：写错一个字母若被当成"没有这个任务"，
            // 现象会是"任务不存在"而人明明看见任务在列表里
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "未知的任务类型: " + kind + "（可用: " + String.join(" / ", KINDS) + "）");
        }
        DecisionLabBundleProvider provider = bundleProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "决策实验室未装配，无法提供执行包");
        }
        LabBundle bundle = provider.labBundle(kind, id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "任务无执行包（不存在或状态不允许执行）: " + kind + "/" + id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                // 包名只用于 runner 侧日志（"物化了哪个包"），带上比让节点自己编个名字有用
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName(bundle.fileName()) + "\"")
                .contentLength(bundle.zip().length)
                .body(bundle.zip());
    }

    /** 包名进 Content-Disposition 前去掉引号/换行（响应头注入，别让实验室里起的名字能改响应头） */
    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "lab-bundle.zip";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "lab-bundle.zip" : cleaned;
    }
}

package com.devmind.agent.controller;

import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.model.ClassifyPackageProvider;
import com.devmind.common.model.ClassifyPackageProvider.ClassifyPackageFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-57 分类安装包拉取端点：runner 收 pkg 帧后凭节点 token 走 HTTP 拉取包文件
 * （边车程序包/模型权重包/语料包，GB 级），落盘校验 sha256 再解包。
 *
 * <p>与 CAP-56 执行包端点（{@link AgentLabBundleController}）同构：SecurityConfig 对该前缀
 * permitAll，节点 token 在控制器内判定；内容由 common 的 {@link ClassifyPackageProvider} SPI
 * 供给，agent 模块不反向依赖 classify 模块。</p>
 *
 * <p><b>流式契约</b>：返回 {@link FileSystemResource} 由 servlet 容器流式写出，严禁读成
 * byte[]——模型权重包可达 GB 级，进内存即 OOM。</p>
 */
@RestController
@RequestMapping("/api/agent/classify/packages")
public class AgentClassifyPackageController {

    private final AgentNodeService nodeService;
    private final ObjectProvider<ClassifyPackageProvider> packageProvider;

    public AgentClassifyPackageController(AgentNodeService nodeService,
                                          ObjectProvider<ClassifyPackageProvider> packageProvider) {
        this.nodeService = nodeService;
        this.packageProvider = packageProvider;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id,
                                                       @RequestParam(required = false) String token) {
        if (nodeService.resolveByToken(token).isEmpty()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "拉取安装包需要有效节点 token");
        }
        ClassifyPackageProvider provider = packageProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "classify 模块未装配");
        }
        ClassifyPackageFile file = provider.packageFile(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "安装包不存在: id=" + id));
        if (!java.nio.file.Files.isRegularFile(file.path())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "安装包文件缺失（可能被清理或移动）: id=" + id);
        }
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }
}

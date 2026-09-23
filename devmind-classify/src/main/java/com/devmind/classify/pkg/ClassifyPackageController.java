package com.devmind.classify.pkg;

import com.devmind.classify.pkg.dto.ClassifyPackageInstallView;
import com.devmind.classify.pkg.dto.ClassifyPackageView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CAP-57 FR-03 安装包管理端点。上传走 multipart 流式（上限见 application.yml
 * {@code spring.servlet.multipart}，CAP-57 起 4GB）；分发是 202 语义——返回 PENDING 行，
 * 结果经 installs 查询或前端轮询。
 */
@RestController
@RequestMapping("/api/classify/packages")
public class ClassifyPackageController {

    private final ClassifyPackageService service;

    public ClassifyPackageController(ClassifyPackageService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClassifyPackageView> list(@RequestParam(required = false) String kind) {
        return service.list(kind);
    }

    @GetMapping("/{id}")
    public ClassifyPackageView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/upload")
    public ClassifyPackageView upload(@RequestParam String kind, @RequestParam String name,
                                      @RequestParam("version") String pkgVersion,
                                      @RequestParam("file") MultipartFile file) {
        return service.upload(kind, name, pkgVersion, file);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** 分发到节点：202 立即返回 PENDING，pkg_ack 异步收口 INSTALLED/FAILED */
    @PostMapping("/{id}/install")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClassifyPackageInstallView install(@PathVariable long id, @RequestParam String nodeId) {
        return service.install(id, nodeId);
    }
}

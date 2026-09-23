package com.devmind.classify.pkg;

import com.devmind.classify.pkg.dto.ClassifyPackageInstallView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAP-57 FR-03 安装记录端点：按包/节点查询 + 失败重试。 */
@RestController
@RequestMapping("/api/classify/installs")
public class ClassifyInstallController {

    private final ClassifyPackageService service;

    public ClassifyInstallController(ClassifyPackageService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClassifyPackageInstallView> list(@RequestParam(required = false) Long packageId,
                                                 @RequestParam(required = false) String nodeId) {
        return service.listInstalls(packageId, nodeId);
    }

    @PostMapping("/{id}/retry")
    public ClassifyPackageInstallView retry(@PathVariable long id) {
        return service.retryInstall(id);
    }
}

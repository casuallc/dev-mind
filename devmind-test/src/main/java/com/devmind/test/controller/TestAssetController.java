package com.devmind.test.controller;

import com.devmind.test.dto.TestAssetGitRequest;
import com.devmind.test.dto.TestAssetReviewRequest;
import com.devmind.test.dto.TestAssetView;
import com.devmind.test.service.TestAssetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** CAP-104 FR-04 测试资产：git 登记、ZIP/文件上传、评审门禁与删除保护。 */
@RestController
@RequestMapping("/api")
public class TestAssetController {

    private final TestAssetService service;

    public TestAssetController(TestAssetService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/test-assets")
    public List<TestAssetView> list(@PathVariable String projectId,
                                    @RequestParam(required = false) String reviewStatus) {
        return service.list(projectId, reviewStatus);
    }

    @GetMapping("/test-assets/{id}")
    public TestAssetView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/projects/{projectId}/test-assets/git")
    public TestAssetView createGit(@PathVariable String projectId, @RequestBody TestAssetGitRequest req) {
        return service.createGit(projectId, req);
    }

    @PostMapping("/projects/{projectId}/test-assets/upload")
    public TestAssetView upload(@PathVariable String projectId,
                                @RequestParam("file") MultipartFile file,
                                @RequestParam(required = false) String name,
                                @RequestParam(required = false) String note,
                                @RequestParam(required = false) String assetRole) {
        return service.upload(projectId, file, name, note, assetRole);
    }

    /** M1.5 程序包清单原文（前端展示与排障；非程序资产 404）。 */
    @GetMapping("/test-assets/{id}/manifest")
    public String manifest(@PathVariable Long id) {
        return service.manifestJson(id);
    }

    @PostMapping("/test-assets/{id}/review")
    public TestAssetView review(@PathVariable Long id, @RequestBody TestAssetReviewRequest req) {
        return service.review(id, req);
    }

    @DeleteMapping("/test-assets/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}

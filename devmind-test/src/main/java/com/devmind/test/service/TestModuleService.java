package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.TestModuleRequest;
import com.devmind.test.dto.TestModuleView;
import com.devmind.test.model.TestModuleEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestModuleRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** CAP-104 功能模块（项目 → 模块 → 套件 → 用例）CRUD，套件按 moduleId 归组。 */
@Service
public class TestModuleService {

    private final TestModuleRepository repo;
    private final TestSuiteRepository suiteRepo;
    private final ProjectService projectService;

    public TestModuleService(TestModuleRepository repo, TestSuiteRepository suiteRepo,
                             ProjectService projectService) {
        this.repo = repo;
        this.suiteRepo = suiteRepo;
        this.projectService = projectService;
    }

    public List<TestModuleView> list(String projectId) {
        projectService.requireProject(projectId);
        return repo.findByProjectIdOrderBySortNoAscNameAsc(projectId).stream().map(this::toView).toList();
    }

    public TestModuleView create(String projectId, TestModuleRequest req) {
        projectService.requireProject(projectId);
        String name = requireName(req);
        if (repo.existsByProjectIdAndName(projectId, name)) {
            throw new DevMindException(ErrorCode.CONFLICT, "模块已存在: " + name);
        }
        TestModuleEntity e = new TestModuleEntity();
        e.setProjectId(projectId);
        apply(e, req, name);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return toView(repo.save(e));
    }

    public TestModuleView update(Long id, TestModuleRequest req) {
        TestModuleEntity e = require(id);
        String name = requireName(req);
        repo.findByProjectIdAndName(e.getProjectId(), name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DevMindException(ErrorCode.CONFLICT, "模块已存在: " + name);
                });
        apply(e, req, name);
        e.setUpdatedAt(Instant.now());
        return toView(repo.save(e));
    }

    /** 删除模块：套件变为未分类（不级联删除测试资产）。 */
    @Transactional
    public void delete(Long id) {
        TestModuleEntity e = require(id);
        List<TestSuiteEntity> suites = suiteRepo.findByModuleId(id);
        for (TestSuiteEntity s : suites) {
            s.setModuleId(null);
        }
        suiteRepo.saveAll(suites);
        repo.delete(e);
    }

    public TestModuleEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试模块不存在: " + id));
    }

    private void apply(TestModuleEntity e, TestModuleRequest req, String name) {
        e.setName(name);
        e.setDescription(blankToNull(req.description()));
        e.setTags(blankToNull(req.tags()));
        e.setOwner(blankToNull(req.owner()));
        e.setSortNo(req.sortNo() == null ? 100 : req.sortNo());
    }

    private String requireName(TestModuleRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "模块名称必填");
        }
        String name = req.name().strip();
        if (name.length() > 128) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "模块名称超长（≤128）");
        }
        return name;
    }

    private TestModuleView toView(TestModuleEntity e) {
        return new TestModuleView(e.getId(), e.getProjectId(), e.getName(), e.getDescription(), e.getTags(),
                e.getOwner(), e.getSortNo(), suiteRepo.countByModuleId(e.getId()), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

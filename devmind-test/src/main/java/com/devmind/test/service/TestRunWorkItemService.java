package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.test.dto.TestRunWorkItemRequest;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestEvidenceEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestEvidenceRepository;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CAP-104 FR-17：失败用例 → 工作单元/缺陷，正文带运行、用例、断言差异与证据链接。 */
@Service
public class TestRunWorkItemService {

    private static final int ASSERTION_LIMIT = 8000;

    private final TestRunRepository runRepo;
    private final TestCaseResultRepository resultRepo;
    private final TestEvidenceRepository evidenceRepo;
    private final WorkItemService workItemService;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final AuditService auditService;

    public TestRunWorkItemService(TestRunRepository runRepo, TestCaseResultRepository resultRepo,
                                  TestEvidenceRepository evidenceRepo, WorkItemService workItemService,
                                  ProjectService projectService, IdentityService identityService,
                                  AuditService auditService) {
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.evidenceRepo = evidenceRepo;
        this.workItemService = workItemService;
        this.projectService = projectService;
        this.identityService = identityService;
        this.auditService = auditService;
    }

    @Transactional
    public WorkItemView create(Long runId, TestRunWorkItemRequest req) {
        TestRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试运行不存在: " + runId));
        projectService.requireProject(run.getProjectId());
        if (req == null || req.resultId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "resultId 必填");
        }
        TestCaseResultEntity result = resultRepo.findById(req.resultId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例结果不存在: " + req.resultId()));
        if (!runId.equals(result.getRunId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "用例结果不属于运行 " + runId);
        }
        String status = result.getStatus() == null ? "" : result.getStatus().toLowerCase();
        if (!List.of("fail", "error", "blocked").contains(status)) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有失败/异常/阻塞结果可以创建工作单元");
        }
        String requirementId = req.requirementId() != null && !req.requirementId().isBlank()
                ? req.requirementId().strip() : run.getRequirementId();
        if (requirementId == null || requirementId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "运行未关联需求，请在请求中指定 requirementId");
        }
        String title = req.title() != null && !req.title().isBlank()
                ? req.title().strip()
                : "测试失败: " + (result.getName() == null ? "用例 " + result.getCaseId() : result.getName());
        if (title.length() > 190) {
            title = title.substring(0, 190);
        }
        String spec = req.spec() != null && !req.spec().isBlank() ? req.spec().strip() : autoSpec(run, result);
        WorkItemView created = workItemService.create(run.getProjectId(), requirementId,
                new WorkItemRequest(req.type() == null || req.type().isBlank() ? "DEVELOPMENT" : req.type(),
                        title, spec, null, null, null));
        if (run.getWorkItemId() == null || run.getWorkItemId().isBlank()) {
            run.setWorkItemId(created.id());
            runRepo.save(run);
        }
        auditService.record("test", "run.work-item", identityService.currentActor(), run.getProjectId(), true,
                "运行 #" + runId + " 结果 #" + result.getId() + " → 工作单元 " + created.id());
        return created;
    }

    private String autoSpec(TestRunEntity run, TestCaseResultEntity result) {
        StringBuilder md = new StringBuilder();
        md.append("## 来源\n\n");
        md.append("- 测试运行: #").append(run.getId());
        if (run.getPlanRunId() != null) {
            md.append(" · 方案运行 #").append(run.getPlanRunId());
        }
        md.append("\n- 用例: ").append(result.getName())
                .append(" (#").append(result.getCaseId()).append(")\n");
        md.append("- 结果: #").append(result.getId()).append(" · ").append(result.getStatus()).append("\n");
        if (result.getFailureCategory() != null) {
            md.append("- 失败类别: ").append(result.getFailureCategory()).append("\n");
        }
        if (result.getNodeId() != null) {
            md.append("- 节点: `").append(result.getNodeId()).append("`\n");
        }
        if (run.getShardKey() != null) {
            md.append("- 分片: `").append(run.getShardKey()).append("`\n");
        }
        if (result.getCommand() != null && !result.getCommand().isBlank()) {
            md.append("- 命令: `").append(result.getCommand()).append("`");
            if (result.getExitCode() != null) {
                md.append(" · 退出码 ").append(result.getExitCode());
            }
            md.append("\n");
        }
        md.append("- 耗时: ").append(result.getDuration() == null ? "—" : result.getDuration() + " ms").append("\n");
        md.append("- 报告: `/api/test-runs/").append(run.getId()).append("/report`\n");

        if (result.getError() != null && !result.getError().isBlank()) {
            md.append("\n## 失败信息\n\n```\n").append(cap(result.getError(), 4000)).append("\n```\n");
        }
        if (result.getAssertionJson() != null && !result.getAssertionJson().isBlank()) {
            md.append("\n## 断言差异\n\n```json\n")
                    .append(cap(result.getAssertionJson(), ASSERTION_LIMIT)).append("\n```\n");
        }
        List<TestEvidenceEntity> evidence = evidenceRepo.findByRunIdOrderBySeqNoAsc(run.getId()).stream()
                .filter(e -> e.getResultId() == null || e.getResultId().equals(result.getId()))
                .toList();
        if (!evidence.isEmpty()) {
            md.append("\n## 证据\n\n");
            for (TestEvidenceEntity e : evidence) {
                md.append("- [").append(e.getKind()).append("] ").append(e.getFilename())
                        .append(" · `/api/test-evidence/").append(e.getId()).append("/download`")
                        .append(e.getSha256() == null ? "" : " · sha256 `" + e.getSha256() + "`")
                        .append("\n");
            }
        }
        return md.toString();
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }
}

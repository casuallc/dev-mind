package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.TaskRequest;
import com.devmind.project.dto.TaskView;
import com.devmind.project.model.TaskEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Task 主线：项目内主线工作项，Task 内嵌 Requirement（title/description 即需求内容），每个 Task 一条独立流程。
 * 只做"身份 + 状态 + 关联"：状态人工/API 驱动，转换规则不写死（流程编排属上层，后续组合叠加）。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final Set<String> STATUSES = Set.of(
            TaskEntity.STATUS_DRAFT,
            TaskEntity.STATUS_DESIGNING,
            TaskEntity.STATUS_DEVELOPING,
            TaskEntity.STATUS_TESTING,
            TaskEntity.STATUS_ACCEPTANCE,
            TaskEntity.STATUS_DONE,
            TaskEntity.STATUS_CANCELLED);

    private final ProjectRepository projectRepo;
    private final TaskRepository taskRepo;

    public TaskService(ProjectRepository projectRepo, TaskRepository taskRepo) {
        this.projectRepo = projectRepo;
        this.taskRepo = taskRepo;
    }

    public List<TaskView> list(String projectId, String status) {
        requireProject(projectId);
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return taskRepo.findByProjectIdOrderBySeqDesc(projectId).stream().map(this::toView).toList();
        }
        return taskRepo.findByProjectIdAndStatusOrderBySeqDesc(projectId, normalizeStatus(status))
                .stream().map(this::toView).toList();
    }

    public TaskView get(String projectId, String taskId) {
        return toView(requireEntity(projectId, taskId));
    }

    /** 创建任务：DRAFT 起步，seq 项目内自增（synchronized 防并发重号，(project_id, seq) 唯一约束兜底）。 */
    public synchronized TaskView create(String projectId, TaskRequest req) {
        requireProject(projectId);
        TaskEntity e = new TaskEntity();
        e.setId(shortId());
        e.setProjectId(projectId);
        Long max = taskRepo.findMaxSeqByProjectId(projectId);
        e.setSeq(max == null ? 1 : max + 1);
        e.setTitle(req.title().trim());
        e.setDescription(blankToNull(req.description()));
        e.setStatus(TaskEntity.STATUS_DRAFT);
        e.setOwnerId(blankToNull(req.ownerId()));
        e.setBranchSlug(req.branchSlug() == null || req.branchSlug().isBlank()
                ? slugify(req.title()) : slugify(req.branchSlug()));
        e.setDocId(req.docId());
        e.setCreatedBy("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        taskRepo.save(e);
        log.info("任务已创建: projectId={} code={} title={}", projectId, code(e.getSeq()), e.getTitle());
        return toView(e);
    }

    public TaskView update(String projectId, String taskId, TaskRequest req) {
        TaskEntity e = requireEntity(projectId, taskId);
        if (req.title() != null && !req.title().isBlank()) e.setTitle(req.title().trim());
        if (req.description() != null) e.setDescription(blankToNull(req.description()));
        if (req.ownerId() != null) e.setOwnerId(blankToNull(req.ownerId()));
        if (req.branchSlug() != null) e.setBranchSlug(blankToNull(slugify(req.branchSlug())));
        if (req.docId() != null) e.setDocId(req.docId());
        e.setUpdatedAt(Instant.now());
        return toView(taskRepo.save(e));
    }

    /** 状态推进：只校验状态值合法，不限制转换路径（流程规则留给上层组合）。 */
    public TaskView updateStatus(String projectId, String taskId, String status) {
        TaskEntity e = requireEntity(projectId, taskId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        TaskView view = toView(taskRepo.save(e));
        log.info("任务状态推进: {} {} -> {}", code(e.getSeq()), prev, next);
        return view;
    }

    public void delete(String projectId, String taskId) {
        TaskEntity e = requireEntity(projectId, taskId);
        taskRepo.delete(e);
        log.info("任务已删除: projectId={} code={}", projectId, code(e.getSeq()));
    }

    /** 任务分支名（P0-6 约定）：task/<seq>-<slug> */
    public String branchName(TaskEntity e) {
        return "task/" + e.getSeq() + (e.getBranchSlug() == null || e.getBranchSlug().isBlank()
                ? "" : "-" + e.getBranchSlug());
    }

    /** 供其他模块按 id 校验任务归属（关联字段写入前校验）。 */
    public TaskEntity requireEntity(String projectId, String taskId) {
        return taskRepo.findById(taskId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
    }

    /** 按 id 直查（不要求项目上下文），供会话等用 taskId 反推 projectId。 */
    public TaskEntity requireById(String taskId) {
        return taskRepo.findById(taskId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
    }

    private void requireProject(String projectId) {
        projectRepo.findById(projectId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: " + projectId));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "任务状态不能为空");
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法任务状态: " + status + "（可选 " + String.join("/", STATUSES) + "）");
        }
        return s;
    }

    /** 标题/输入转分支 slug：小写字母数字与连字符，最长 48。 */
    private String slugify(String text) {
        if (text == null) {
            return "";
        }
        String slug = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9一-鿿]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    private String code(Long seq) {
        return "TASK-" + seq;
    }

    private TaskView toView(TaskEntity e) {
        return new TaskView(e.getId(), e.getProjectId(), e.getSeq(), code(e.getSeq()), e.getTitle(),
                e.getDescription(), e.getStatus(), e.getOwnerId(), e.getBranchSlug(), e.getDocId(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String shortId() {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(base.charAt(ThreadLocalRandom.current().nextInt(base.length())));
        }
        return sb.toString();
    }
}

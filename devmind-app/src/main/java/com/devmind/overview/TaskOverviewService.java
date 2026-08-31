package com.devmind.overview;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.docs.model.DocumentEntity;
import com.devmind.docs.repo.DocumentRepository;
import com.devmind.overview.dto.TaskOverviewView;
import com.devmind.project.TaskService;
import com.devmind.project.dto.TaskView;
import com.devmind.project.model.TaskEntity;
import com.devmind.release.model.ReleaseEntity;
import com.devmind.release.repo.ReleaseRepository;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 任务主线聚合（P0-6 步骤 4）：任务详情页 = 按 (projectId, taskId) 聚合任务全过程对象
 * （含 CAP-11 发版）。放在 app 组装层——project 是基石模块，不反向依赖 build/deploy/test/docs/session/release。
 */
@Service
public class TaskOverviewService {

    private final TaskService taskService;
    private final DocumentRepository docRepo;
    private final SessionRepository sessionRepo;
    private final BuildRepository buildRepo;
    private final TestRunRepository testRunRepo;
    private final DeploymentRepository deploymentRepo;
    private final ReleaseRepository releaseRepo;

    public TaskOverviewService(TaskService taskService,
                               DocumentRepository docRepo,
                               SessionRepository sessionRepo,
                               BuildRepository buildRepo,
                               TestRunRepository testRunRepo,
                               DeploymentRepository deploymentRepo,
                               ReleaseRepository releaseRepo) {
        this.taskService = taskService;
        this.docRepo = docRepo;
        this.sessionRepo = sessionRepo;
        this.buildRepo = buildRepo;
        this.testRunRepo = testRunRepo;
        this.deploymentRepo = deploymentRepo;
        this.releaseRepo = releaseRepo;
    }

    public TaskOverviewView overview(String projectId, String taskId) {
        TaskEntity task = taskService.requireEntity(projectId, taskId);
        TaskView taskView = taskService.get(projectId, taskId);

        List<TaskOverviewView.DocItem> docs = docRepo
                .findByTaskIdOrderByUpdatedAtDesc(taskId).stream()
                .map(d -> new TaskOverviewView.DocItem(d.getId(), d.getKind(), d.getTitle(), d.getStatus(),
                        d.getCurrentVersion(), d.getUpdatedAt()))
                .toList();
        List<TaskOverviewView.SessionItem> sessions = sessionRepo
                .findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(s -> new TaskOverviewView.SessionItem(s.getId(), s.getStatus(),
                        preview(s.getTaskSpec()), s.getModel(), s.getCreatedAt(), s.getFinishedAt()))
                .toList();
        List<TaskOverviewView.BuildItem> builds = buildRepo
                .findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(b -> new TaskOverviewView.BuildItem(b.getId(), b.getStatus(), b.getBranch(),
                        b.getCommit(), b.getArtifactRef(), b.getCreatedAt(), b.getFinishedAt()))
                .toList();
        List<TaskOverviewView.TestRunItem> testRuns = testRunRepo
                .findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(t -> new TaskOverviewView.TestRunItem(t.getId(), t.getStatus(), t.getSummaryJson(),
                        t.getReportDocId(), t.getTriggeredBy(), t.getCreatedAt(), t.getFinishedAt()))
                .toList();
        List<TaskOverviewView.DeploymentItem> deployments = deploymentRepo
                .findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(d -> new TaskOverviewView.DeploymentItem(d.getId(), d.getStatus(), d.getEnv(),
                        d.getServerId(), d.getBuildId(), d.getCreatedBy(), d.getCreatedAt(), d.getFinishedAt()))
                .toList();
        List<TaskOverviewView.ReleaseItem> releases = releaseRepo
                .findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(r -> new TaskOverviewView.ReleaseItem(r.getId(), r.getReleaseVersion(), r.getStatus(),
                        r.getExecutor(), r.getRollbackOf(), r.getCreatedAt(), r.getFinishedAt()))
                .toList();

        return new TaskOverviewView(taskView, docs, sessions, builds, testRuns, deployments, releases,
                timeline(task, docs, sessions, builds, testRuns, deployments, releases));
    }

    /** 跨类型时间线（倒序）：任务创建/状态更新 + 各对象创建与完结。无独立事件表前的轻量实现。 */
    private List<TaskOverviewView.TimelineItem> timeline(
            TaskEntity task,
            List<TaskOverviewView.DocItem> docs,
            List<TaskOverviewView.SessionItem> sessions,
            List<TaskOverviewView.BuildItem> builds,
            List<TaskOverviewView.TestRunItem> testRuns,
            List<TaskOverviewView.DeploymentItem> deployments,
            List<TaskOverviewView.ReleaseItem> releases) {
        List<TaskOverviewView.TimelineItem> items = new ArrayList<>();
        items.add(new TaskOverviewView.TimelineItem(task.getCreatedAt(), "TASK",
                "任务创建：" + task.getTitle(), task.getId()));
        if (task.getUpdatedAt() != null && !task.getUpdatedAt().equals(task.getCreatedAt())) {
            items.add(new TaskOverviewView.TimelineItem(task.getUpdatedAt(), "TASK",
                    "任务更新（当前状态 " + task.getStatus() + "）", task.getId()));
        }
        docs.forEach(d -> items.add(new TaskOverviewView.TimelineItem(d.updatedAt(), "DOC",
                "文档[" + d.kind() + "] " + d.title() + "（v" + d.currentVersion() + " " + d.status() + "）",
                String.valueOf(d.id()))));
        sessions.forEach(s -> {
            items.add(new TaskOverviewView.TimelineItem(s.createdAt(), "SESSION",
                    "会话启动：" + s.taskSpec(), s.id()));
            if (s.finishedAt() != null) {
                items.add(new TaskOverviewView.TimelineItem(s.finishedAt(), "SESSION",
                        "会话结束（" + s.status() + "）", s.id()));
            }
        });
        builds.forEach(b -> {
            items.add(new TaskOverviewView.TimelineItem(b.createdAt(), "BUILD",
                    "构建 #" + b.id() + " 触发（" + (b.branch() == null ? "-" : b.branch()) + "）",
                    String.valueOf(b.id())));
            if (b.finishedAt() != null) {
                items.add(new TaskOverviewView.TimelineItem(b.finishedAt(), "BUILD",
                        "构建 #" + b.id() + " " + b.status(), String.valueOf(b.id())));
            }
        });
        testRuns.forEach(t -> {
            items.add(new TaskOverviewView.TimelineItem(t.createdAt(), "TEST_RUN",
                    "测试运行 #" + t.id() + " 开始（" + t.triggeredBy() + "）", String.valueOf(t.id())));
            if (t.finishedAt() != null) {
                items.add(new TaskOverviewView.TimelineItem(t.finishedAt(), "TEST_RUN",
                        "测试运行 #" + t.id() + " " + t.status(), String.valueOf(t.id())));
            }
        });
        deployments.forEach(d -> {
            items.add(new TaskOverviewView.TimelineItem(d.createdAt(), "DEPLOYMENT",
                    "部署 #" + d.id() + " 创建（env=" + (d.env() == null ? "-" : d.env()) + "）",
                    String.valueOf(d.id())));
            if (d.finishedAt() != null) {
                items.add(new TaskOverviewView.TimelineItem(d.finishedAt(), "DEPLOYMENT",
                        "部署 #" + d.id() + " " + d.status(), String.valueOf(d.id())));
            }
        });
        releases.forEach(r -> {
            items.add(new TaskOverviewView.TimelineItem(r.createdAt(), "RELEASE",
                    "发版 " + (r.version() == null ? "#" + r.id() : r.version()) + " 创建（" + r.status() + "）",
                    String.valueOf(r.id())));
            if (r.finishedAt() != null) {
                items.add(new TaskOverviewView.TimelineItem(r.finishedAt(), "RELEASE",
                        "发版 " + (r.version() == null ? "#" + r.id() : r.version()) + " " + r.status(),
                        String.valueOf(r.id())));
            }
        });
        items.sort(Comparator.comparing(TaskOverviewView.TimelineItem::time,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}

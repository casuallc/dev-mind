package com.devmind.overview;

import com.devmind.artifact.repo.ArtifactRepository;
import com.devmind.build.repo.BuildRepository;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.docs.repo.DocumentRepository;
import com.devmind.overview.dto.RequirementOverviewView;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.release.repo.ReleaseRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 需求主线聚合（CAP-13）：需求详情页 = 按 (projectId, requirementId) 聚合需求全过程对象
 * （工作单元 + 文档/会话/构建/测试/部署/发版/产物）。
 * 放在 app 组装层——project 是基石模块，不反向依赖 build/deploy/test/docs/session/release/artifact。
 * 会话按 requirementId 聚合（含分析型会话，创建时与 workItem 的 requirementId 一致写入）；
 * 构建/测试/部署/发版按 workItemId 集合聚合。
 */
@Service
public class RequirementOverviewService {

    private final RequirementService requirementService;
    private final WorkItemService workItemService;
    private final DocumentRepository docRepo;
    private final SessionRepository sessionRepo;
    private final BuildRepository buildRepo;
    private final TestRunRepository testRunRepo;
    private final DeploymentRepository deploymentRepo;
    private final ReleaseRepository releaseRepo;
    private final ArtifactRepository artifactRepo;

    public RequirementOverviewService(RequirementService requirementService,
                                      WorkItemService workItemService,
                                      DocumentRepository docRepo,
                                      SessionRepository sessionRepo,
                                      BuildRepository buildRepo,
                                      TestRunRepository testRunRepo,
                                      DeploymentRepository deploymentRepo,
                                      ReleaseRepository releaseRepo,
                                      ArtifactRepository artifactRepo) {
        this.requirementService = requirementService;
        this.workItemService = workItemService;
        this.docRepo = docRepo;
        this.sessionRepo = sessionRepo;
        this.buildRepo = buildRepo;
        this.testRunRepo = testRunRepo;
        this.deploymentRepo = deploymentRepo;
        this.releaseRepo = releaseRepo;
        this.artifactRepo = artifactRepo;
    }

    public RequirementOverviewView overview(String projectId, String requirementId) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        RequirementView requirementView = requirementService.get(projectId, requirementId);
        List<WorkItemView> workItems = workItemService.list(projectId, requirementId);
        List<String> workItemIds = workItems.stream().map(WorkItemView::id).toList();

        List<RequirementOverviewView.DocItem> docs = docRepo
                .findByRequirementIdOrderByUpdatedAtDesc(requirementId).stream()
                .map(d -> new RequirementOverviewView.DocItem(d.getId(), d.getKind(), d.getTitle(), d.getStatus(),
                        d.getCurrentVersion(), d.getUpdatedAt()))
                .toList();
        List<RequirementOverviewView.SessionItem> sessions = sessionRepo
                .findByRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                .map(s -> new RequirementOverviewView.SessionItem(s.getId(), s.getStatus(),
                        preview(s.getTaskSpec()), s.getModel(), s.getWorkItemId(),
                        s.getCreatedAt(), s.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.BuildItem> builds = workItemIds.isEmpty() ? List.of()
                : buildRepo.findByWorkItemIdInOrderByCreatedAtDesc(workItemIds).stream()
                .map(b -> new RequirementOverviewView.BuildItem(b.getId(), b.getStatus(), b.getBranch(),
                        b.getCommit(), b.getArtifactRef(), b.getWorkItemId(), b.getCreatedAt(), b.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.TestRunItem> testRuns = workItemIds.isEmpty() ? List.of()
                : testRunRepo.findByWorkItemIdInOrderByCreatedAtDesc(workItemIds).stream()
                .map(t -> new RequirementOverviewView.TestRunItem(t.getId(), t.getStatus(), t.getSummaryJson(),
                        t.getReportDocId(), t.getTriggeredBy(), t.getWorkItemId(), t.getCreatedAt(), t.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.DeploymentItem> deployments = workItemIds.isEmpty() ? List.of()
                : deploymentRepo.findByWorkItemIdInOrderByCreatedAtDesc(workItemIds).stream()
                .map(d -> new RequirementOverviewView.DeploymentItem(d.getId(), d.getStatus(), d.getEnv(),
                        d.getServerId(), d.getBuildId(), d.getWorkItemId(), d.getCreatedBy(),
                        d.getCreatedAt(), d.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.ReleaseItem> releases = workItemIds.isEmpty() ? List.of()
                : releaseRepo.findByWorkItemIdInOrderByCreatedAtDesc(workItemIds).stream()
                .map(r -> new RequirementOverviewView.ReleaseItem(r.getId(), r.getReleaseVersion(), r.getStatus(),
                        r.getExecutor(), r.getRollbackOf(), r.getCreatedAt(), r.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.ArtifactItem> artifacts = artifactRepo
                .findByProjectIdAndRequirementIdOrderByIdDesc(projectId, requirementId).stream()
                .map(a -> new RequirementOverviewView.ArtifactItem(a.getId(), a.getType(), a.getName(),
                        a.getPath(), a.getProducerType(), a.getCreatedAt()))
                .toList();

        return new RequirementOverviewView(requirementView, workItems, docs, sessions, builds, testRuns,
                deployments, releases, artifacts,
                timeline(requirement, workItems, docs, sessions, builds, testRuns, deployments, releases, artifacts));
    }

    /** 跨类型时间线（倒序）：需求创建/状态更新 + 工作单元推进 + 各对象创建与完结。无独立事件表前的轻量实现。 */
    private List<RequirementOverviewView.TimelineItem> timeline(
            RequirementEntity requirement,
            List<WorkItemView> workItems,
            List<RequirementOverviewView.DocItem> docs,
            List<RequirementOverviewView.SessionItem> sessions,
            List<RequirementOverviewView.BuildItem> builds,
            List<RequirementOverviewView.TestRunItem> testRuns,
            List<RequirementOverviewView.DeploymentItem> deployments,
            List<RequirementOverviewView.ReleaseItem> releases,
            List<RequirementOverviewView.ArtifactItem> artifacts) {
        List<RequirementOverviewView.TimelineItem> items = new ArrayList<>();
        items.add(new RequirementOverviewView.TimelineItem(requirement.getCreatedAt(), "REQUIREMENT",
                "需求创建：" + requirement.getTitle(), requirement.getId()));
        if (requirement.getUpdatedAt() != null && !requirement.getUpdatedAt().equals(requirement.getCreatedAt())) {
            items.add(new RequirementOverviewView.TimelineItem(requirement.getUpdatedAt(), "REQUIREMENT",
                    "需求更新（当前状态 " + requirement.getStatus() + "）", requirement.getId()));
        }
        workItems.forEach(w -> {
            items.add(new RequirementOverviewView.TimelineItem(w.createdAt(), "WORK_ITEM",
                    "工作单元创建[" + w.type() + "]：" + w.title(), w.id()));
            if (w.updatedAt() != null && !w.updatedAt().equals(w.createdAt())) {
                items.add(new RequirementOverviewView.TimelineItem(w.updatedAt(), "WORK_ITEM",
                        "工作单元更新[" + w.type() + "] " + w.title() + "（当前状态 " + w.status() + "）", w.id()));
            }
        });
        docs.forEach(d -> items.add(new RequirementOverviewView.TimelineItem(d.updatedAt(), "DOC",
                "文档[" + d.kind() + "] " + d.title() + "（v" + d.currentVersion() + " " + d.status() + "）",
                String.valueOf(d.id()))));
        sessions.forEach(s -> {
            items.add(new RequirementOverviewView.TimelineItem(s.createdAt(), "SESSION",
                    "会话启动：" + s.taskSpec(), s.id()));
            if (s.finishedAt() != null) {
                items.add(new RequirementOverviewView.TimelineItem(s.finishedAt(), "SESSION",
                        "会话结束（" + s.status() + "）", s.id()));
            }
        });
        builds.forEach(b -> {
            items.add(new RequirementOverviewView.TimelineItem(b.createdAt(), "BUILD",
                    "构建 #" + b.id() + " 触发（" + (b.branch() == null ? "-" : b.branch()) + "）",
                    String.valueOf(b.id())));
            if (b.finishedAt() != null) {
                items.add(new RequirementOverviewView.TimelineItem(b.finishedAt(), "BUILD",
                        "构建 #" + b.id() + " " + b.status(), String.valueOf(b.id())));
            }
        });
        testRuns.forEach(t -> {
            items.add(new RequirementOverviewView.TimelineItem(t.createdAt(), "TEST_RUN",
                    "测试运行 #" + t.id() + " 开始（" + t.triggeredBy() + "）", String.valueOf(t.id())));
            if (t.finishedAt() != null) {
                items.add(new RequirementOverviewView.TimelineItem(t.finishedAt(), "TEST_RUN",
                        "测试运行 #" + t.id() + " " + t.status(), String.valueOf(t.id())));
            }
        });
        deployments.forEach(d -> {
            items.add(new RequirementOverviewView.TimelineItem(d.createdAt(), "DEPLOYMENT",
                    "部署 #" + d.id() + " 创建（env=" + (d.env() == null ? "-" : d.env()) + "）",
                    String.valueOf(d.id())));
            if (d.finishedAt() != null) {
                items.add(new RequirementOverviewView.TimelineItem(d.finishedAt(), "DEPLOYMENT",
                        "部署 #" + d.id() + " " + d.status(), String.valueOf(d.id())));
            }
        });
        releases.forEach(r -> {
            items.add(new RequirementOverviewView.TimelineItem(r.createdAt(), "RELEASE",
                    "发版 " + (r.version() == null ? "#" + r.id() : r.version()) + " 创建（" + r.status() + "）",
                    String.valueOf(r.id())));
            if (r.finishedAt() != null) {
                items.add(new RequirementOverviewView.TimelineItem(r.finishedAt(), "RELEASE",
                        "发版 " + (r.version() == null ? "#" + r.id() : r.version()) + " " + r.status(),
                        String.valueOf(r.id())));
            }
        });
        artifacts.forEach(a -> items.add(new RequirementOverviewView.TimelineItem(a.createdAt(), "ARTIFACT",
                "产物登记[" + a.type() + "] " + (a.name() == null ? "-" : a.name()), String.valueOf(a.id()))));
        items.sort(Comparator.comparing(RequirementOverviewView.TimelineItem::time,
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

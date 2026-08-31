package com.devmind.overview;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.docs.model.DocumentEntity;
import com.devmind.docs.repo.DocumentRepository;
import com.devmind.overview.dto.RequirementOverviewView;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
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
 * 需求主线聚合（P0-6 步骤 4）：需求详情页 = 按 (projectId, requirementId) 聚合。
 * 放在 app 组装层——project 是基石模块，不反向依赖 build/deploy/test/docs/session。
 */
@Service
public class RequirementOverviewService {

    private final RequirementService requirementService;
    private final DocumentRepository docRepo;
    private final SessionRepository sessionRepo;
    private final BuildRepository buildRepo;
    private final TestRunRepository testRunRepo;
    private final DeploymentRepository deploymentRepo;

    public RequirementOverviewService(RequirementService requirementService,
                                      DocumentRepository docRepo,
                                      SessionRepository sessionRepo,
                                      BuildRepository buildRepo,
                                      TestRunRepository testRunRepo,
                                      DeploymentRepository deploymentRepo) {
        this.requirementService = requirementService;
        this.docRepo = docRepo;
        this.sessionRepo = sessionRepo;
        this.buildRepo = buildRepo;
        this.testRunRepo = testRunRepo;
        this.deploymentRepo = deploymentRepo;
    }

    public RequirementOverviewView overview(String projectId, String reqId) {
        RequirementEntity req = requirementService.requireEntity(projectId, reqId);
        RequirementView reqView = requirementService.get(projectId, reqId);

        List<RequirementOverviewView.DocItem> docs = docRepo
                .findByRequirementIdOrderByUpdatedAtDesc(reqId).stream()
                .map(d -> new RequirementOverviewView.DocItem(d.getId(), d.getKind(), d.getTitle(), d.getStatus(),
                        d.getCurrentVersion(), d.getUpdatedAt()))
                .toList();
        List<RequirementOverviewView.SessionItem> sessions = sessionRepo
                .findByRequirementIdOrderByCreatedAtDesc(reqId).stream()
                .map(s -> new RequirementOverviewView.SessionItem(s.getId(), s.getStatus(),
                        preview(s.getTaskSpec()), s.getModel(), s.getCreatedAt(), s.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.BuildItem> builds = buildRepo
                .findByRequirementIdOrderByCreatedAtDesc(reqId).stream()
                .map(b -> new RequirementOverviewView.BuildItem(b.getId(), b.getStatus(), b.getBranch(),
                        b.getCommit(), b.getArtifactRef(), b.getCreatedAt(), b.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.TestRunItem> testRuns = testRunRepo
                .findByRequirementIdOrderByCreatedAtDesc(reqId).stream()
                .map(t -> new RequirementOverviewView.TestRunItem(t.getId(), t.getStatus(), t.getSummaryJson(),
                        t.getReportDocId(), t.getTriggeredBy(), t.getCreatedAt(), t.getFinishedAt()))
                .toList();
        List<RequirementOverviewView.DeploymentItem> deployments = deploymentRepo
                .findByRequirementIdOrderByCreatedAtDesc(reqId).stream()
                .map(d -> new RequirementOverviewView.DeploymentItem(d.getId(), d.getStatus(), d.getEnv(),
                        d.getServerId(), d.getBuildId(), d.getCreatedBy(), d.getCreatedAt(), d.getFinishedAt()))
                .toList();

        return new RequirementOverviewView(reqView, docs, sessions, builds, testRuns, deployments,
                timeline(req, docs, sessions, builds, testRuns, deployments));
    }

    /** 跨类型时间线（倒序）：需求创建/状态更新 + 各对象创建与完结。无独立事件表前的轻量实现。 */
    private List<RequirementOverviewView.TimelineItem> timeline(
            RequirementEntity req,
            List<RequirementOverviewView.DocItem> docs,
            List<RequirementOverviewView.SessionItem> sessions,
            List<RequirementOverviewView.BuildItem> builds,
            List<RequirementOverviewView.TestRunItem> testRuns,
            List<RequirementOverviewView.DeploymentItem> deployments) {
        List<RequirementOverviewView.TimelineItem> items = new ArrayList<>();
        items.add(new RequirementOverviewView.TimelineItem(req.getCreatedAt(), "REQUIREMENT",
                "需求创建：" + req.getTitle(), req.getId()));
        if (req.getUpdatedAt() != null && !req.getUpdatedAt().equals(req.getCreatedAt())) {
            items.add(new RequirementOverviewView.TimelineItem(req.getUpdatedAt(), "REQUIREMENT",
                    "需求更新（当前状态 " + req.getStatus() + "）", req.getId()));
        }
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

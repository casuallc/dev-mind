package com.devmind.dashboard;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.dashboard.dto.DashboardView;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.project.model.DesignEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.DesignRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.release.model.ReleaseEntity;
import com.devmind.release.repo.ReleaseRepository;
import com.devmind.session.model.SessionState;
import com.devmind.session.repo.SessionRepository;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指挥中心（CAP-16）：全局聚合只读视图，放 app 组装层（与 RequirementOverview 同模式）。
 * 各能力仓库 findAll 后内存聚合——本地规模足够，数据量上来后再换专用查询。
 */
@Service
public class DashboardService {

    private static final Set<String> ACTIVE_SESSION_STATUSES = Set.of(
            SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(), SessionState.WAITING_AUTH.name());

    private static final List<String> REQUIREMENT_STATUSES = List.of(
            RequirementEntity.STATUS_DRAFT, RequirementEntity.STATUS_ANALYZING,
            RequirementEntity.STATUS_DESIGNING, RequirementEntity.STATUS_IN_PROGRESS,
            RequirementEntity.STATUS_ACCEPTANCE, RequirementEntity.STATUS_DONE,
            RequirementEntity.STATUS_CANCELLED);

    private static final int MAX_FAILURES = 10;

    private final RequirementRepository requirementRepo;
    private final DesignRepository designRepo;
    private final SessionRepository sessionRepo;
    private final BuildRepository buildRepo;
    private final DeploymentRepository deploymentRepo;
    private final TestRunRepository testRunRepo;
    private final ReleaseRepository releaseRepo;

    public DashboardService(RequirementRepository requirementRepo,
                            DesignRepository designRepo,
                            SessionRepository sessionRepo,
                            BuildRepository buildRepo,
                            DeploymentRepository deploymentRepo,
                            TestRunRepository testRunRepo,
                            ReleaseRepository releaseRepo) {
        this.requirementRepo = requirementRepo;
        this.designRepo = designRepo;
        this.sessionRepo = sessionRepo;
        this.buildRepo = buildRepo;
        this.deploymentRepo = deploymentRepo;
        this.testRunRepo = testRunRepo;
        this.releaseRepo = releaseRepo;
    }

    public DashboardView dashboard() {
        Map<String, Long> requirements = new LinkedHashMap<>();
        REQUIREMENT_STATUSES.forEach(s -> requirements.put(s, 0L));
        List<DashboardView.PendingRequirementItem> pendingAcceptance = new ArrayList<>();
        for (RequirementEntity r : requirementRepo.findAll()) {
            requirements.merge(r.getStatus(), 1L, Long::sum);
            if (RequirementEntity.STATUS_ACCEPTANCE.equals(r.getStatus())) {
                pendingAcceptance.add(new DashboardView.PendingRequirementItem(
                        r.getId(), r.getProjectId(), "REQ-" + r.getSeq(), r.getTitle()));
            }
        }

        List<DashboardView.ActiveSessionItem> activeSessions = sessionRepo.findAll().stream()
                .filter(s -> ACTIVE_SESSION_STATUSES.contains(s.getStatus()))
                .sorted(Comparator.comparing(com.devmind.session.model.SessionEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> new DashboardView.ActiveSessionItem(s.getId(), s.getProjectId(), s.getRequirementId(),
                        s.getWorkItemId(), preview(s.getTaskSpec()), s.getStatus(), s.getCreatedAt()))
                .toList();

        List<DashboardView.PendingDesignItem> pendingDesigns = designRepo.findAll().stream()
                .filter(d -> DesignEntity.STATUS_DRAFT.equals(d.getStatus()))
                .map(d -> new DashboardView.PendingDesignItem(d.getId(), d.getProjectId(),
                        d.getRequirementId(), d.getVersion(), d.getDocId()))
                .toList();

        List<DashboardView.FailureItem> failures = new ArrayList<>();
        for (BuildEntity b : buildRepo.findAll()) {
            if (BuildEntity.FAILED.equals(b.getStatus())) {
                failures.add(new DashboardView.FailureItem("BUILD", String.valueOf(b.getId()), b.getProjectId(),
                        "构建 #" + b.getId() + (b.getBranch() == null ? "" : "（" + b.getBranch() + "）"),
                        time(b.getFinishedAt(), b.getCreatedAt())));
            }
        }
        for (DeploymentEntity d : deploymentRepo.findAll()) {
            if (DeploymentEntity.FAILED.equals(d.getStatus())) {
                failures.add(new DashboardView.FailureItem("DEPLOYMENT", String.valueOf(d.getId()), d.getProjectId(),
                        "部署 #" + d.getId() + (d.getEnv() == null ? "" : "（" + d.getEnv() + "）"),
                        time(d.getFinishedAt(), d.getCreatedAt())));
            }
        }
        for (TestRunEntity t : testRunRepo.findAll()) {
            if (TestRunEntity.FAILED.equals(t.getStatus())) {
                failures.add(new DashboardView.FailureItem("TEST_RUN", String.valueOf(t.getId()), t.getProjectId(),
                        "测试运行 #" + t.getId(),
                        time(t.getFinishedAt(), t.getCreatedAt())));
            }
        }
        for (ReleaseEntity r : releaseRepo.findAll()) {
            if (ReleaseEntity.FAILED.equals(r.getStatus())) {
                failures.add(new DashboardView.FailureItem("RELEASE", String.valueOf(r.getId()), r.getProjectId(),
                        "发版 " + (r.getReleaseVersion() == null ? "#" + r.getId() : r.getReleaseVersion()),
                        time(r.getFinishedAt(), r.getCreatedAt())));
            }
        }
        List<DashboardView.FailureItem> recentFailures = failures.stream()
                .sorted(Comparator.comparing(DashboardView.FailureItem::time,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_FAILURES)
                .toList();

        return new DashboardView(requirements, activeSessions, pendingAcceptance, pendingDesigns, recentFailures);
    }

    private Instant time(Instant finishedAt, Instant createdAt) {
        return finishedAt != null ? finishedAt : createdAt;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}

package com.devmind.project;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.AuthorizationException;
import com.devmind.common.authz.AuthorizationService;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.common.authz.SubjectType;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.DesignRepository;
import com.devmind.project.repo.EnvironmentRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RelationRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CAP-108 M1 Service 边界钉线：requirement.create / work_item.create 必过核心授权
 * （操作 id 与目标项目归属正确；无认证上下文 → 引擎拒绝即抛出，不落库）。
 * 无 Spring 上下文，repository 用 JDK 动态代理。
 */
class ServiceAuthzBoundaryTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** 记录调用的授权桩：require 无条件放行（引擎自身的拒绝矩阵由 CoreAuthorizationServiceTest 覆盖）。 */
    static class RecordingAuthz implements AuthorizationService {
        final List<String> calls = new ArrayList<>();

        @Override
        public void require(String operationId, ResourceTarget target) {
            calls.add(operationId + " @" + target.projectId());
        }

        @Override
        public AuthorizationDecision check(String operationId, ResourceTarget target) {
            return AuthorizationDecision.allow();
        }
    }

    static class FakeIdentityService extends com.devmind.auth.IdentityService {
        FakeIdentityService() { super(null, null, null); }
        @Override public String currentActor() { return "tester"; }
    }

    private final RecordingAuthz authz = new RecordingAuthz();
    private RequirementService requirementService;
    private WorkItemService workItemService;

    @BeforeEach
    void setUp() {
        ProjectEntity project = new ProjectEntity();
        project.setId("p1");
        RequirementEntity requirement = new RequirementEntity();
        requirement.setId("r1");
        requirement.setProjectId("p1");
        requirement.setSeq(1L);
        requirement.setStatus(RequirementEntity.STATUS_DRAFT);

        InvocationHandler untouchable = (p, m, args) -> {
            throw new UnsupportedOperationException(m.getName());
        };
        ProjectRepository projectRepo = proxy(ProjectRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findById" -> Optional.of(project);
            default -> throw new UnsupportedOperationException(m.getName());
        });
        RequirementRepository requirementRepo = proxy(RequirementRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findById" -> Optional.of(requirement);
            case "findMaxSeqByProjectId" -> null;
            case "save" -> args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        WorkItemRepository workItemRepo = proxy(WorkItemRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findMaxSeqByProjectId" -> null;
            case "findByRequirementIdOrderBySeqAsc" -> List.of();
            case "save" -> args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<Object> nullProvider = proxy(ObjectProvider.class,
                (p, m, args) -> "getIfAvailable".equals(m.getName()) ? null : null);
        requirementService = new RequirementService(projectRepo, requirementRepo, workItemRepo,
                proxy(DesignRepository.class, untouchable),
                proxy(RelationRepository.class, untouchable),
                new FakeIdentityService(),
                (ObjectProvider<RequirementExternalRefLookup>) (Object) nullProvider,
                (ObjectProvider<RequirementAgentTimeLookup>) (Object) nullProvider,
                new DomainEventPublisher(null) {
                    @Override public void publish(DomainEvent event) { }
                },
                proxy(EnvironmentRepository.class, untouchable),
                authz);
        workItemService = new WorkItemService(requirementService, workItemRepo, requirementRepo,
                proxy(DesignRepository.class, untouchable),
                proxy(RelationRepository.class, untouchable),
                new DomainEventPublisher(null) {
                    @Override public void publish(DomainEvent event) { }
                },
                new FakeIdentityService(), authz);
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
    }

    private static RequirementRequest requirementRequest() {
        return new RequirementRequest("标题", null, null, null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void 创建需求经核心授权且目标为项目() {
        requirementService.create("p1", requirementRequest());
        assertEquals(List.of("requirement.create @p1"), authz.calls);
    }

    @Test
    void 创建工作单元经核心授权且项目归属取服务端反查值() {
        workItemService.create("p1", "r1", new WorkItemRequest(WorkItemEntity.TYPE_DEVELOPMENT,
                "标题", null, null, null, null));
        assertEquals(List.of("work_item.create @p1"), authz.calls);
    }

    @Test
    void 授权拒绝即抛出不落库() {
        AuthorizationService denying = new AuthorizationService() {
            @Override
            public void require(String operationId, ResourceTarget target) {
                throw AuthorizationException.from(AuthorizationDecision.deny(
                        ReasonCodes.SUBJECT_UNKNOWN, "缺少可信执行上下文"), operationId);
            }

            @Override
            public AuthorizationDecision check(String operationId, ResourceTarget target) {
                return AuthorizationDecision.deny(ReasonCodes.SUBJECT_UNKNOWN, "缺少可信执行上下文");
            }
        };
        WorkItemService denyingWi = new WorkItemService(requirementService,
                proxy(WorkItemRepository.class, (p, m, args) -> switch (m.getName()) {
                    case "findMaxSeqByProjectId" -> null;
                    case "save" -> args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                }),
                proxy(RequirementRepository.class, (p, m, args) -> null),
                proxy(DesignRepository.class, (p, m, args) -> null),
                proxy(RelationRepository.class, (p, m, args) -> null),
                new DomainEventPublisher(null) {
                    @Override public void publish(DomainEvent event) { }
                },
                new FakeIdentityService(), denying);
        AuthorizationException e = assertThrows(AuthorizationException.class, () -> denyingWi.create(
                "p1", "r1", new WorkItemRequest(WorkItemEntity.TYPE_DEVELOPMENT, "标题", null, null, null, null)));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void 软读边界有上下文时委托判定() {
        try (var ignored = ExecutionContextHolder.set(
                ExecutionContext.builder(SubjectType.USER, "alice", ExecutionSource.WEB).build())) {
            requirementService.get("p1", "r1");
            assertEquals(List.of("requirement.read @p1"), authz.calls);
        }
    }
}

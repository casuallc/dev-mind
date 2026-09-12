package com.devmind.session.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.CreateSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-38 FR-06 会话关联需求自动建工作单元（{@link SessionManagerService#autoCreateWorkItem}）：
 * 普通执行会话自动建 DEVELOPMENT WI（title=taskSpec 首行截 60 字符，spec=taskSpec）；
 * [flow:*] 流程会话豁免；终态需求（ACCEPTANCE/DONE/CANCELLED）409。
 */
class SessionAutoWorkItemTest {

    private FakeWorkItemService workItemService;
    private SessionManagerService service;

    static class FakeWorkItemService extends WorkItemService {
        final Map<String, WorkItemEntity> store = new LinkedHashMap<>();
        WorkItemRequest lastRequest;
        private int seq = 0;

        FakeWorkItemService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public synchronized WorkItemView create(String projectId, String requirementId, WorkItemRequest req) {
            lastRequest = req;
            WorkItemEntity e = new WorkItemEntity();
            e.setId("wi-" + (++seq));
            e.setProjectId(projectId);
            e.setRequirementId(requirementId);
            e.setType(req.type());
            e.setTitle(req.title());
            e.setSpec(req.spec());
            e.setStatus(WorkItemEntity.STATUS_TODO);
            store.put(e.getId(), e);
            return new WorkItemView(e.getId(), projectId, requirementId, null, 1L, "WI-1",
                    e.getType(), e.getTitle(), e.getSpec(), e.getStatus(),
                    null, null, "test", Instant.now(), Instant.now());
        }

        @Override
        public WorkItemEntity requireById(String workItemId) {
            return store.get(workItemId);
        }
    }

    @BeforeEach
    void setUp() {
        workItemService = new FakeWorkItemService();
        service = new SessionManagerService(null, null, workItemService, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RequirementEntity requirement(String status) {
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(1L);
        e.setTitle("登录优化");
        e.setStatus(status);
        return e;
    }

    private static CreateSessionRequest request(String taskSpec) {
        return new CreateSessionRequest(null, "p1", null, "r1", taskSpec, null, null, null, null);
    }

    @Test
    void 普通会话_自动建DEVELOPMENT工作单元() {
        WorkItemEntity wi = service.autoCreateWorkItem(request("实现登录接口\n细节略"),
                requirement(RequirementEntity.STATUS_DRAFT));

        assertEquals(wi, workItemService.store.get(wi.getId()));
        assertEquals(WorkItemEntity.TYPE_DEVELOPMENT, workItemService.lastRequest.type());
        assertEquals("实现登录接口", workItemService.lastRequest.title());
        assertEquals("实现登录接口\n细节略", workItemService.lastRequest.spec());
    }

    @Test
    void 标题_首行超60字符截断() {
        String longFirstLine = "实现一个非常非常非常长的登录接口功能".repeat(5);
        service.autoCreateWorkItem(request(longFirstLine), requirement(RequirementEntity.STATUS_IN_PROGRESS));
        assertEquals(60, workItemService.lastRequest.title().length());
    }

    @Test
    void 流程会话_flow标记豁免不建WI() {
        assertNull(service.autoCreateWorkItem(request("[flow:analyze]\n分析任务"),
                requirement(RequirementEntity.STATUS_DRAFT)));
        assertNull(service.autoCreateWorkItem(request("[flow:split]\n拆分任务"),
                requirement(RequirementEntity.STATUS_ANALYZING)));
        assertTrue(workItemService.store.isEmpty());
    }

    @Test
    void 空taskSpec豁免不建WI() {
        assertNull(service.autoCreateWorkItem(request(null), requirement(RequirementEntity.STATUS_DRAFT)));
        assertTrue(workItemService.store.isEmpty());
    }

    @Test
    void 终态需求_409拒绝() {
        for (String status : new String[]{RequirementEntity.STATUS_ACCEPTANCE,
                RequirementEntity.STATUS_DONE, RequirementEntity.STATUS_CANCELLED}) {
            DevMindException e = assertThrows(DevMindException.class,
                    () -> service.autoCreateWorkItem(request("做点什么"), requirement(status)));
            assertTrue(e.getMessage().contains(status));
        }
        assertTrue(workItemService.store.isEmpty());
    }

    @Test
    void 进行中状态_允许建WI() {
        for (String status : new String[]{RequirementEntity.STATUS_DRAFT,
                RequirementEntity.STATUS_ANALYZING, RequirementEntity.STATUS_DESIGNING,
                RequirementEntity.STATUS_IN_PROGRESS}) {
            WorkItemEntity wi = service.autoCreateWorkItem(request("执行任务"), requirement(status));
            assertEquals(WorkItemEntity.STATUS_TODO, wi.getStatus());
        }
        assertEquals(4, workItemService.store.size());
    }
}

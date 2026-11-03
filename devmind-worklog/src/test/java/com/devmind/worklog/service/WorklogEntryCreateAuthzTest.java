package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.AuthorizationException;
import com.devmind.common.authz.AuthorizationService;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-108 M1 工作日志写边界钉线：worklog.entry.create 必过核心授权，
 * 目标归属主体取服务端身份（identity.currentActor()），拒绝即抛出不落库。
 */
class WorklogEntryCreateAuthzTest {

    /** 记录调用的授权桩（引擎拒绝矩阵由 CoreAuthorizationServiceTest 覆盖）。 */
    static class RecordingAuthz implements AuthorizationService {
        final List<String> calls = new ArrayList<>();

        @Override
        public void require(String operationId, ResourceTarget target) {
            calls.add(operationId + " @" + target.ownerId());
        }

        @Override
        public AuthorizationDecision check(String operationId, ResourceTarget target) {
            return AuthorizationDecision.allow();
        }
    }

    private static EntryRequest req() {
        return new EntryRequest(LocalDate.of(2026, 11, 4), "写周报", null, "DEV", 1.0,
                null, null, null, null, null);
    }

    @Test
    void 创建条目经核心授权且归属为当前用户() {
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn("alice");
        RecordingAuthz authz = new RecordingAuthz();
        WorklogEntryService service = new WorklogEntryService(entryRepo,
                mock(WorklogPlanItemRepository.class), identity, null, authz);

        service.create(req());

        assertEquals(List.of("worklog.entry.create @alice"), authz.calls);
    }

    @Test
    void 授权拒绝即抛出不落库() {
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn("alice");
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
        WorklogEntryService service = new WorklogEntryService(mock(WorklogEntryRepository.class),
                mock(WorklogPlanItemRepository.class), identity, null, denying);

        AuthorizationException e = assertThrows(AuthorizationException.class, () -> service.create(req()));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }
}

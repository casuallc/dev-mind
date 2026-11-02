package com.devmind.worklog.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogRepoSubscriptionRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日类任务（日报/Git 导入）的目标日期接线：tick 早于中午 12 点生成前一天，
 * 否则生成当天（口径本体在 {@link WorklogScheduleTest}）。runBatch 走虚拟线程异步执行，
 * 测试用 CountDownLatch 等回调落定再断言。
 */
class WorklogSchedulerTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String USER = "alice";

    private ReportService reportService;
    private GitAutoImportService gitAutoImport;
    private WorklogUserSettingsRepository settingsRepo;
    private WorklogRepoSubscriptionRepository subRepo;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        gitAutoImport = mock(GitAutoImportService.class);
        settingsRepo = mock(WorklogUserSettingsRepository.class);
        subRepo = mock(WorklogRepoSubscriptionRepository.class);
        when(subRepo.findDistinctUserIds()).thenReturn(List.of(USER));
    }

    private WorklogScheduler scheduler(WorklogProperties props) {
        return new WorklogScheduler(reportService, gitAutoImport, settingsRepo, subRepo, props,
                mock(DomainEventPublisher.class));
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 3, 0, ZONE);
    }

    private static WorklogUserSettingsEntity gitImportUser(String time) {
        WorklogUserSettingsEntity s = new WorklogUserSettingsEntity();
        s.setUserId(USER);
        s.setAutoGitImport(Boolean.TRUE);
        s.setGitImportTime(time);
        return s;
    }

    // ---- 日报 ----

    @Test
    void dailyEveningTickGeneratesSameDay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(reportService).generateDaily(anyString(), any(), anyBoolean(), isNull());

        scheduler(new WorklogProperties()).dailyBatch(at(28, 18, 30), Map.of()); // 全局默认 18:30

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(reportService).generateDaily(eq(USER), date.capture(), eq(false), isNull());
        assertEquals(LocalDate.of(2026, 10, 28), date.getValue());
    }

    @Test
    void dailyMorningTickGeneratesPreviousDay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(reportService).generateDaily(anyString(), any(), anyBoolean(), isNull());

        WorklogProperties morning = new WorklogProperties();
        morning.setDailyCron("0 30 9 * * *");
        scheduler(morning).dailyBatch(at(28, 9, 30), Map.of());

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(reportService).generateDaily(eq(USER), date.capture(), eq(false), isNull());
        assertEquals(LocalDate.of(2026, 10, 27), date.getValue());
    }

    // ---- Git 导入 ----

    @Test
    void gitImportEveningTickImportsSameDay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(gitAutoImport).importForDate(anyString(), any());

        scheduler(new WorklogProperties()).gitImportBatch(at(28, 18, 0),
                Map.of(USER, gitImportUser(null))); // 跟随全局 18:00

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(gitAutoImport).importForDate(eq(USER), date.capture());
        assertEquals(LocalDate.of(2026, 10, 28), date.getValue());
    }

    @Test
    void gitImportMorningTickImportsPreviousDay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(gitAutoImport).importForDate(anyString(), any());

        scheduler(new WorklogProperties()).gitImportBatch(at(28, 9, 0),
                Map.of(USER, gitImportUser("09:00"))); // 个人执行时间 09:00

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(gitAutoImport).importForDate(eq(USER), date.capture());
        assertEquals(LocalDate.of(2026, 10, 27), date.getValue());
    }
}

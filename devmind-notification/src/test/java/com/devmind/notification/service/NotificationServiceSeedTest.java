package com.devmind.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devmind.notification.model.NotificationChannelEntity;
import com.devmind.notification.model.NotificationPrefsEntity;
import com.devmind.notification.repo.NotificationChannelRepository;
import com.devmind.notification.repo.NotificationPrefsRepository;
import com.devmind.notification.repo.NotificationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** CAP-75 FR-02：通道种子逐 code 兜底——存量库补插缺失 code、不覆盖既有配置。 */
class NotificationServiceSeedTest {

    private final NotificationChannelRepository channelRepo = mock(NotificationChannelRepository.class);
    private final NotificationPrefsRepository prefsRepo = mock(NotificationPrefsRepository.class);

    private NotificationService service() {
        return new NotificationService(mock(NotificationRepository.class), channelRepo, prefsRepo,
                null, new ObjectMapper(), List.of(), null);
    }

    @Test
    void seedsAllChannelsOnEmptyDatabase() {
        when(channelRepo.findByCode(any())).thenReturn(Optional.empty());
        when(prefsRepo.findById("local")).thenReturn(Optional.of(new NotificationPrefsEntity()));

        service().seed();

        ArgumentCaptor<NotificationChannelEntity> captor = ArgumentCaptor.forClass(NotificationChannelEntity.class);
        verify(channelRepo, times(5)).save(captor.capture());
        List<String> codes = new ArrayList<>();
        captor.getAllValues().forEach(e -> codes.add(e.getCode()));
        assertEquals(List.of("ws", "log", "bark", "wecom", "feishu"), codes);
        NotificationChannelEntity feishu = captor.getAllValues().get(4);
        assertTrue(feishu.getConfigJson().contains("webhookUrl"));
        assertTrue(feishu.getConfigJson().contains("secret"));
        assertEquals("P0", feishu.getLevelThreshold());
    }

    @Test
    void existingChannelsAreNotTouched() {
        NotificationChannelEntity existing = new NotificationChannelEntity();
        existing.setCode("ws");
        existing.setEnabled(false); // 用户改过配置
        existing.setConfigJson("{\"custom\":1}");
        when(channelRepo.findByCode("ws")).thenReturn(Optional.of(existing));
        when(channelRepo.findByCode("log")).thenReturn(Optional.empty());
        when(channelRepo.findByCode("bark")).thenReturn(Optional.empty());
        when(channelRepo.findByCode("wecom")).thenReturn(Optional.empty());
        when(channelRepo.findByCode("feishu")).thenReturn(Optional.empty());
        when(prefsRepo.findById("local")).thenReturn(Optional.of(new NotificationPrefsEntity()));

        service().seed();

        ArgumentCaptor<NotificationChannelEntity> captor = ArgumentCaptor.forClass(NotificationChannelEntity.class);
        verify(channelRepo, times(4)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(e -> "ws".equals(e.getCode())),
                "既有 ws 行不应被重写");
        assertTrue(captor.getAllValues().stream().anyMatch(e -> "feishu".equals(e.getCode())));
    }

    @Test
    void nothingSeededWhenAllPresent() {
        when(channelRepo.findByCode(any())).thenReturn(Optional.of(new NotificationChannelEntity()));
        when(prefsRepo.findById("local")).thenReturn(Optional.of(new NotificationPrefsEntity()));

        service().seed();

        verify(channelRepo, never()).save(any());
    }
}

package com.devmind.openapi.service;

import com.devmind.auth.IdentityService;
import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.repo.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ApiKeyService service = new ApiKeyService(repo, identity);

    @Test
    void 签发返回明文一次且只存哈希() {
        when(identity.currentActor()).thenReturn("admin");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object[] issued = service.issue("ci-bot", null);
        String secret = (String) issued[0];
        ApiKeyEntity saved = (ApiKeyEntity) issued[1];

        assertTrue(secret.startsWith("sk_"));
        assertEquals(51, secret.length()); // sk_ + 48 hex
        assertTrue(saved.getAccessKey().startsWith("ak_"));
        // 明文不落库：库里是 sha256(sk)
        assertEquals(ApiKeyService.sha256Hex(secret), saved.getSecretHash());
        assertNotEquals(secret, saved.getSecretHash());
        assertEquals(Boolean.TRUE, saved.getEnabled());
        assertEquals("admin", saved.getCreatedBy());
    }

    @Test
    void 校验通过条件为存在且启用且未过期() {
        ApiKeyEntity k = key(true, null);
        when(repo.findByAccessKey("ak_x")).thenReturn(Optional.of(k));
        assertTrue(service.findVerifiable("ak_x").isPresent());
    }

    @Test
    void 禁用后校验失败() {
        when(repo.findByAccessKey("ak_x")).thenReturn(Optional.of(key(false, null)));
        assertTrue(service.findVerifiable("ak_x").isEmpty());
    }

    @Test
    void 过期后校验失败() {
        when(repo.findByAccessKey("ak_x"))
                .thenReturn(Optional.of(key(true, Instant.now().minusSeconds(60))));
        assertTrue(service.findVerifiable("ak_x").isEmpty());
    }

    @Test
    void 启停写库() {
        ApiKeyEntity k = key(true, null);
        k.setId(7L);
        when(repo.findById(7L)).thenReturn(Optional.of(k));
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.setEnabled(7L, false);
        assertEquals(Boolean.FALSE, captor.getValue().getEnabled());
    }

    private static ApiKeyEntity key(boolean enabled, Instant expiresAt) {
        ApiKeyEntity k = new ApiKeyEntity();
        k.setAccessKey("ak_x");
        k.setSecretHash(ApiKeyService.sha256Hex("sk_test"));
        k.setName("t");
        k.setEnabled(enabled);
        k.setExpiresAt(expiresAt);
        return k;
    }
}

package com.devmind.onboarding;

import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.service.ApiKeyService;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * CAP-20 AI 项目接入助手（open-api 的首个客户端）：
 * 签发一次性 2h API 密钥 → 渲染内置 prompt → 起一个不挂项目的 bypassPermissions 会话，
 * 由 agent 用 scripts/openapi.sh 把用户描述的项目配置自动写入平台。
 */
@Service
public class OnboardingService {

    /** 一次性密钥有效期：2 小时，覆盖一次接入会话足够 */
    private static final long KEY_TTL_HOURS = 2;

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final ApiKeyService apiKeyService;
    private final SessionManagerService sessionManager;

    public OnboardingService(ApiKeyService apiKeyService, SessionManagerService sessionManager) {
        this.apiKeyService = apiKeyService;
        this.sessionManager = sessionManager;
    }

    /**
     * 发起接入：返回新会话 ID（前端跳转会话页实时观看）。
     */
    public String start(String description) {
        Object[] issued = apiKeyService.issue("onboard-" + Instant.now().getEpochSecond(),
                Instant.now().plus(KEY_TTL_HOURS, ChronoUnit.HOURS));
        String secret = (String) issued[0];
        ApiKeyEntity key = (ApiKeyEntity) issued[1];

        String prompt = OnboardingPrompt.render(description, key.getAccessKey(), secret);
        // 不挂项目的裸会话 + bypassPermissions：接入是全自动运维动作，发起入口本身已限定 ADMIN
        SessionView view = sessionManager.create(new CreateSessionRequest(
                null, null, null, null, prompt, null, null, "bypassPermissions"));
        log.info("AI 接入会话已启动: session={} key={}", view.id(), key.getAccessKey());
        return view.id();
    }
}

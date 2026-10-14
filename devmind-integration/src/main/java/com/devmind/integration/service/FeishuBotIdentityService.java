package com.devmind.integration.service;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.UserPlatformAccountRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * CAP-75 FR-11 飞书机器人个人身份解析：senderId(open_id) → 平台用户名。
 * 在「启用了 bot 槽的 ENABLED FEISHU 集成」集合里查 user_platform_accounts
 * （authType=IDENTITY，username=open_id）的绑定，多集成依次查、首个命中生效。
 * 由 devmind-feishu-bot 适配器经 {@code BotChannelConfigProvider.resolveActor} 调用，
 * bot 底座保持不依赖本模块。
 */
@Service
public class FeishuBotIdentityService {

    private final IntegrationRepository integrationRepo;
    private final UserPlatformAccountRepository accountRepo;
    private final UserRepository userRepo;

    public FeishuBotIdentityService(IntegrationRepository integrationRepo,
                                    UserPlatformAccountRepository accountRepo,
                                    UserRepository userRepo) {
        this.integrationRepo = integrationRepo;
        this.accountRepo = accountRepo;
        this.userRepo = userRepo;
    }

    /** open_id → 平台用户名；未绑定（或绑定用户已删）返回 empty */
    public Optional<String> resolveUsername(String senderOpenId) {
        if (senderOpenId == null || senderOpenId.isBlank()) {
            return Optional.empty();
        }
        for (IntegrationEntity i : integrationRepo.findAllByOrderByCreatedAtDesc()) {
            if (!IntegrationEntity.TYPE_FEISHU.equals(i.getType())
                    || !IntegrationEntity.STATUS_ENABLED.equals(i.getStatus())
                    || !FeishuBotGateway.botEnabled(i)) {
                continue;
            }
            Optional<String> hit = accountRepo.findByIntegrationIdAndUsername(i.getId(), senderOpenId)
                    .filter(a -> IntegrationEntity.AUTH_IDENTITY.equals(a.getAuthType()))
                    .flatMap(a -> userRepo.findById(a.getUserId()))
                    .map(UserEntity::getUsername);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }
}

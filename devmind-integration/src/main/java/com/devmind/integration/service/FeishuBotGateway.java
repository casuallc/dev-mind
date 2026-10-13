package com.devmind.integration.service;

import com.devmind.integration.connector.feishu.FeishuApiClient;
import com.devmind.integration.connector.feishu.FeishuConnector;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-75 FR-04：飞书机器人句柄桥接。从 ENABLED 的 FEISHU 集成解密出 appId/appSecret
 * （密文不出本模块），连同 API 客户端与机器人配置（configJson 的 {"bot":{...}} 槽）
 * 一起交给飞书适配器；无启用集成 = empty（适配器降级禁用）。
 */
@Service
public class FeishuBotGateway {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotGateway.class);

    /**
     * @param appId/appSecret 建连凭据明文（内存传递，不落库不写日志）
     * @param baseUrl         开放平台域名（可指向 mock，E2E 用）
     * @param defaultActor    机器人默认操作身份（configJson bot.defaultActor，可空）
     * @param api             消息回推客户端
     */
    public record FeishuBotHandle(String appId, String appSecret, String baseUrl,
                                  String defaultActor, FeishuApiClient api) {
    }

    private final IntegrationRepository integrationRepo;
    private final IntegrationService integrationService;
    private final FeishuConnector feishuConnector;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeishuBotGateway(IntegrationRepository integrationRepo,
                            IntegrationService integrationService,
                            FeishuConnector feishuConnector) {
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
        this.feishuConnector = feishuConnector;
    }

    /** 取第一个 ENABLED 的飞书集成构造机器人句柄；无则 empty。 */
    public Optional<FeishuBotHandle> botHandle() {
        List<IntegrationEntity> list = integrationRepo.findByTypeAndStatus(
                IntegrationEntity.TYPE_FEISHU, IntegrationEntity.STATUS_ENABLED);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        IntegrationEntity e = list.get(0);
        if (list.size() > 1) {
            log.warn("存在多个启用的飞书集成，机器人用第一个: {}", e.getName());
        }
        String plaintext = integrationService.tokenOf(e);
        FeishuConnector.AppCredentials cred = feishuConnector.credentials(plaintext);
        return Optional.of(new FeishuBotHandle(cred.appId(), cred.appSecret(),
                e.getBaseUrl() == null || e.getBaseUrl().isBlank()
                        ? FeishuConnector.DEFAULT_BASE_URL : e.getBaseUrl(),
                botDefaultActor(e), feishuConnector.apiClient(e, plaintext)));
    }

    /** configJson 扩展槽 {"bot":{"defaultActor":"..."}} */
    private String botDefaultActor(IntegrationEntity e) {
        if (e.getConfigJson() == null || e.getConfigJson().isBlank()) {
            return null;
        }
        try {
            JsonNode bot = mapper.readTree(e.getConfigJson()).path("bot");
            String actor = bot.path("defaultActor").asText("");
            return actor.isBlank() ? null : actor;
        } catch (Exception ex) {
            log.warn("飞书集成 {} 的 configJson 解析失败，忽略 bot 槽: {}", e.getName(), ex.getMessage());
            return null;
        }
    }
}

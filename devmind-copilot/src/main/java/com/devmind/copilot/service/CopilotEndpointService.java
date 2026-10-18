package com.devmind.copilot.service;

import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.copilot.config.CopilotProperties;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 智能体对话端点解析（CAP-76 FR-06）：显式 id → 平台默认 CHAT；显式 id 必须存在、CHAT、ACTIVE。
 * 不回落 mock、不回落嵌入端点。{@code auto} 模式下按端点记住"不支持 tools"的降级决定。
 */
@Service
public class CopilotEndpointService {

    private static final Logger log = LoggerFactory.getLogger(CopilotEndpointService.class);

    public record Resolved(ModelEndpointView view, String toolMode) {
    }

    private final ObjectProvider<ModelEndpointProvider> providers;
    private final CopilotProperties props;
    private final Set<Long> degraded = ConcurrentHashMap.newKeySet();

    public CopilotEndpointService(ObjectProvider<ModelEndpointProvider> providers, CopilotProperties props) {
        this.providers = providers;
        this.props = props;
    }

    public static final class EndpointUnavailableException extends RuntimeException {
        public EndpointUnavailableException(String message) {
            super(message);
        }
    }

    public Resolved resolve() {
        ModelEndpointProvider provider = providers.getIfAvailable();
        if (provider == null) {
            throw new EndpointUnavailableException("模型端点服务未装配，无法使用智能体");
        }
        ModelEndpointView view;
        String configured = props.getEndpointId();
        if (configured != null && !configured.isBlank()) {
            long id;
            try {
                id = Long.parseLong(configured.trim());
            } catch (NumberFormatException e) {
                throw new EndpointUnavailableException("智能体端点 id 配置非法: " + configured);
            }
            view = provider.activeEndpoint(id)
                    .orElseThrow(() -> new EndpointUnavailableException("智能体端点不存在或已停用: " + id));
            if (!view.chat()) {
                throw new EndpointUnavailableException("配置的端点不是对话（CHAT）端点: " + id);
            }
        } else {
            view = provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)
                    .orElseThrow(() -> new EndpointUnavailableException("未配置智能体对话端点（平台无默认 CHAT 端点）"));
            if (!view.chat()) {
                throw new EndpointUnavailableException("平台默认对话端点类型不符");
            }
        }
        if (view.baseUrl() == null || view.baseUrl().isBlank()
                || view.model() == null || view.model().isBlank()) {
            throw new EndpointUnavailableException("智能体对话端点配置不完整（baseUrl/model 为空）");
        }
        return new Resolved(view, modeFor(view));
    }

    /** 供健康检查：配置是否完整（不实际调用模型）。 */
    public boolean configComplete() {
        try {
            resolve();
            return true;
        } catch (EndpointUnavailableException e) {
            return false;
        }
    }

    public String configureFailure() {
        try {
            resolve();
            return null;
        } catch (EndpointUnavailableException e) {
            return e.getMessage();
        }
    }

    public String modeFor(ModelEndpointView view) {
        String configured = props.getToolMode() == null ? "auto" : props.getToolMode().trim().toLowerCase(Locale.ROOT);
        return switch (configured) {
            case "native" -> "native";
            case "json" -> "json";
            default -> degraded.contains(view.id()) ? "json" : "native";
        };
    }

    /** auto 模式：端点明确不支持 tools → 进程内记住并切 json（WARN，不静默）。 */
    public void degradeToJson(ModelEndpointView view) {
        if (degraded.add(view.id())) {
            log.warn("智能体端点 {}（{}）不支持 tools/function calling，已切换到 json 工具模式",
                    view.id(), view.display());
        }
    }

    public Optional<String> configuredFailure() {
        return Optional.ofNullable(configureFailure());
    }
}

package com.devmind.integration.connector.feishu;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.model.IntegrationEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 飞书连接器（CAP-45）：无 git/MR/issue 能力，仅文档拉取；
 * 连接测试 = tenant_access_token 获取成功（校验 appId/appSecret 有效）。
 * secretEnc 密文内容为 "appId\nappSecret"（BASIC 双行惯例，token 形参即解密后双行文本）。
 */
@Component
public class FeishuConnector implements IntegrationConnector {

    public static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    @Override
    public String type() {
        return IntegrationEntity.TYPE_FEISHU;
    }

    @Override
    public TestResult testConnection(IntegrationEntity cfg, String token) {
        try {
            apiClient(cfg, token).tenantToken();
            return new TestResult(true, "tenant_access_token 获取成功", cfg.getBaseUrl());
        } catch (Exception e) {
            return new TestResult(false, "连接失败：" + e.getMessage(), cfg.getBaseUrl());
        }
    }

    @Override
    public List<ExternalProject> listProjects(IntegrationEntity cfg, String token) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成无项目列表能力");
    }

    @Override
    public MergeRequestRef createMergeRequest(IntegrationEntity cfg, String token, MrSpec spec) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成无 git 能力");
    }

    @Override
    public ReleaseRef createRelease(IntegrationEntity cfg, String token, ReleaseSpec spec) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成无 git 能力");
    }

    /** 从密文双行还原 appId/appSecret 构造 API 客户端 */
    public FeishuApiClient apiClient(IntegrationEntity e, String plaintextSecret) {
        String[] lines = splitSecret(plaintextSecret);
        return new FeishuApiClient(e.getBaseUrl(), lines[0], lines[1]);
    }

    private static String[] splitSecret(String plaintextSecret) {
        if (plaintextSecret == null || !plaintextSecret.contains("\n")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "飞书集成凭证格式应为两行：App ID 与 App Secret");
        }
        String[] lines = plaintextSecret.split("\n", -1);
        if (lines.length < 2 || lines[0].isBlank() || lines[1].isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "飞书集成凭证格式应为两行：App ID 与 App Secret");
        }
        return new String[]{lines[0].trim(), lines[1].trim()};
    }
}

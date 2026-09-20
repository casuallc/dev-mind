package com.devmind.model;

import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.repo.ModelEndpointRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * CAP-48 FR-07 配置迁移（幂等、不阻断启动）：把 CAP-44 的 {@code devmind.knowledge.embedding.*}
 * 变成一条端点记录，让存量部署零改造升级——迁移后 cap44/45/46 的 E2E 脚本一行不改仍全绿。
 *
 * <p>三条分支：{@code provider=mock} → 建 mock 默认端点；{@code baseUrl+model} 齐备 →
 * 建平台默认端点（密钥加密落库，维度留空待首次探测）；都不满足 → <b>不建端点</b>
 * （保持索引 disabled / 检索 LIKE 降级，与 CAP-44 现状一致，不静默造一个 mock）。</p>
 *
 * <p><b>只读不写配置</b>：迁移后用户在 UI 的修改不会被重启覆盖——因为幂等判断是
 * "端点表已有 EMBEDDING 端点就直接返回"，而不是每次启动都按配置重写。</p>
 */
@Component
public class ModelEndpointMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelEndpointMigration.class);

    static final String MOCK_ENDPOINT_NAME = "内置 Mock 向量（测试用）";
    static final String DEFAULT_ENDPOINT_NAME = "平台默认向量端点";

    private final ModelEndpointRepository repo;
    private final ModelCipher cipher;
    private final EmbeddingSeedProperties seed;

    public ModelEndpointMigration(ModelEndpointRepository repo, ModelCipher cipher, EmbeddingSeedProperties seed) {
        this.repo = repo;
        this.cipher = cipher;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            // 迁移失败不阻断启动：端点可在 UI 手工补建，索引保持降级而非服务起不来
            log.warn("模型端点配置迁移失败（不阻断启动，可重启重试或手工新建端点）: {}", e.getMessage());
        }
    }

    private void migrate() {
        if (repo.countByKind(ModelEndpointEntity.KIND_EMBEDDING) > 0) {
            return;
        }
        if (ModelEndpointEntity.PROVIDER_MOCK.equalsIgnoreCase(trim(seed.getProvider()))) {
            ModelEndpointEntity e = base(MOCK_ENDPOINT_NAME, ModelEndpointEntity.PROVIDER_MOCK);
            e.setModel(com.devmind.common.model.ModelEndpointView.MODEL_MOCK);
            e.setDimensions(Math.max(8, seed.getDimensions()));
            repo.save(e);
            log.info("CAP-48 端点迁移：由 provider=mock 生成默认 mock 端点（维度 {}）", e.getDimensions());
            return;
        }
        String baseUrl = trim(seed.getBaseUrl());
        String model = trim(seed.getModel());
        if (!baseUrl.isEmpty() && !model.isEmpty()) {
            ModelEndpointEntity e = base(DEFAULT_ENDPOINT_NAME, ModelEndpointEntity.PROVIDER_OPENAI);
            e.setBaseUrl(baseUrl);
            e.setModel(model);
            e.setApiKeyEnc(cipher.encrypt(blankToNull(seed.getApiKey())));
            // 维度留空：等首次索引或「测试连接」按实际响应探测，避免把配置里的猜测当事实
            repo.save(e);
            log.info("CAP-48 端点迁移：由 devmind.knowledge.embedding.* 生成平台默认端点 model={}", model);
            return;
        }
        log.info("CAP-48 端点迁移：未配置 embedding，不生成端点（索引标 disabled、检索 LIKE 降级）");
    }

    private ModelEndpointEntity base(String name, String provider) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setKind(ModelEndpointEntity.KIND_EMBEDDING);
        e.setName(name);
        e.setProvider(provider);
        e.setStatus(ModelEndpointEntity.STATUS_ACTIVE);
        e.setDefault(true);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

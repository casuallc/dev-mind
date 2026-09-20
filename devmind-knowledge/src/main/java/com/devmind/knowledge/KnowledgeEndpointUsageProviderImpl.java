package com.devmind.knowledge;

import com.devmind.common.model.ModelEndpointUsageProvider;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CAP-48 端点删除保护：把"哪些知识库挂了这个端点"报给 devmind-model。
 *
 * <p>用 SPI 而不是让 devmind-model 直接查 knowledge_bases，是为了不让 model 反向依赖
 * knowledge（本项目的模块依赖单向红线）；副作用也正好——裁剪掉 knowledge 模块的部署里，
 * 这个实现不存在，端点自然就没有引用可言。</p>
 */
@Component
public class KnowledgeEndpointUsageProviderImpl implements ModelEndpointUsageProvider {

    private final KnowledgeBaseRepository kbRepo;

    public KnowledgeEndpointUsageProviderImpl(KnowledgeBaseRepository kbRepo) {
        this.kbRepo = kbRepo;
    }

    @Override
    public List<String> usagesOf(long endpointId) {
        return kbRepo.findByModelEndpointId(endpointId).stream()
                .map(kb -> "知识库：" + (kb.getName() == null ? "#" + kb.getId() : kb.getName()))
                .toList();
    }
}

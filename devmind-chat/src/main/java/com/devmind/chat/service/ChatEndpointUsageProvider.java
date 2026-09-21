package com.devmind.chat.service;

import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.chat.repo.ChatSessionRepository;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.model.ModelEndpointUsageProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-48 FR-01 端点删除保护：把"哪些问答正引用这个端点"报给 devmind-model。
 *
 * <p>只报<b>正在生成</b>的模型问答（DB 状态 RUNNING）：模型问答的实时状态就是按这个口径落库的
 * （见 ChatManagerService 的 onStateChange），所以一次索引查询就够。空闲的问答（WAITING_INPUT）
 * 也能继续提问，但把它算作引用等于"问过一次就永久锁死端点删除权"——真删了之后那场问答再提问
 * 会拿到明确的 409（端点已停用/删除，请新建问答），这是可接受的降级。</p>
 */
@Component
public class ChatEndpointUsageProvider implements ModelEndpointUsageProvider {

    /** 进行中口径：只有正在生成的那一轮才算占用 */
    private static final List<String> LIVE_STATUSES = List.of(SessionState.RUNNING.name());

    private final ChatSessionRepository chatRepo;

    public ChatEndpointUsageProvider(ChatSessionRepository chatRepo) {
        this.chatRepo = chatRepo;
    }

    @Override
    public List<String> usagesOf(long endpointId) {
        return chatRepo.findByModelEndpointIdAndStatusIn(endpointId, LIVE_STATUSES).stream()
                .map(e -> "问答：" + (e.getTitle() == null || e.getTitle().isBlank()
                        ? "#" + e.getId() : e.getTitle()))
                .toList();
    }
}

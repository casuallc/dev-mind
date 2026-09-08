package com.devmind.common.agent;

import com.devmind.common.agent.exec.ContextPackage;

import java.util.Optional;

/**
 * CAP-34 FR-03 上下文包查询 SPI（common 定义，devmind-session 实现，devmind-agent 的
 * {@code GET /api/agent/context/{sessionId}} 端点经 ObjectProvider 探测注入消费——
 * agent 模块不反向依赖 session 实现）。
 */
public interface ContextPackageProvider {

    /**
     * 查会话上下文包。命中装配缓存直接返回；缓存未命中（服务端重启/TTL 过期后 runner
     * 重试拉取）按 DB 中会话/项目数据重建。会话不存在或无上下文内容时返回 empty。
     */
    Optional<ContextPackage> find(String sessionId);
}
